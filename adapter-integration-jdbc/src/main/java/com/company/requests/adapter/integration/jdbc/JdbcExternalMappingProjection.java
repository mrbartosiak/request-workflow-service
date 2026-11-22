package com.company.requests.adapter.integration.jdbc;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.PolicyGuard;
import com.company.requests.domain.CreationOrder;
import com.company.requests.integration.ExternalMapping.Binding;
import com.company.requests.integration.ExternalMapping.Key;
import com.company.requests.integration.ExternalMapping.Target;
import com.company.requests.integration.ExternalMappings.OriginalCreate;
import com.company.requests.integration.ExternalMappings.Projection;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local immutable mapping and minimized original Create proof on the guarded workflow connection. */
public final class JdbcExternalMappingProjection implements Projection, OriginalCreate {
    private final DataSource workflow;
    private final JdbcTemplate jdbc;

    public JdbcExternalMappingProjection(DataSource guardedWorkflow) {
        workflow = Objects.requireNonNull(guardedWorkflow);
        jdbc = new JdbcTemplate(workflow);
    }

    @Override
    public Optional<CreationOrder> committed(PolicyGuard guard, Target target) {
        requireOrganization(guard, target);
        return onWorkflow(connection -> {
            try (var query = connection.prepareStatement("""
                    SELECT created_epoch, created_ordinal FROM requests.external_mapping_create_evidence
                    WHERE organization = ? AND request_type = ? AND ref = ?
                    """)) {
                query.setString(1, guard.organization().value());
                query.setString(2, target.type().name());
                query.setString(3, target.ref().value());
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    var order = new CreationOrder(rows.getLong(1), rows.getLong(2));
                    if (rows.next()) {
                        throw new AuthorityUnavailableException();
                    }
                    return Optional.of(order);
                }
            }
        });
    }

    @Override
    public Optional<Binding> read(PolicyGuard guard, Key key) {
        return onWorkflow(connection -> {
            try (var query = connection.prepareStatement("SELECT " + JdbcMappingRows.COLUMNS + """
                    FROM requests.external_mapping WHERE
                    """ + JdbcMappingRows.KEY)) {
                JdbcMappingRows.find(query, key);
                try (var rows = query.executeQuery()) {
                    var binding = JdbcMappingRows.one(rows, key);
                    binding.ifPresent(value -> requireOrganization(guard, value.association().target()));
                    return binding;
                }
            }
        });
    }

    @Override
    public void save(PolicyGuard guard, Binding binding, boolean original) {
        requireOrganization(guard, binding.association().target());
        onWorkflow(connection -> {
            try (var insert = connection.prepareStatement("INSERT INTO requests.external_mapping ("
                    + JdbcMappingRows.COLUMNS + ", original_effect) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                JdbcMappingRows.write(insert, binding.id(), binding.key(), binding.association(), binding.writer());
                insert.setBoolean(15, original);
                if (insert.executeUpdate() != 1) {
                    throw new AuthorityUnavailableException();
                }
                return null;
            }
        });
    }

    private <T> T onWorkflow(ConnectionCallback<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !(TransactionSynchronizationManager.getResource(workflow) instanceof ConnectionHolder holder)
                || !holder.isSynchronizedWithTransaction()) {
            throw new IllegalStateException("Mapping work requires the owned workflow transaction.");
        }
        try {
            return jdbc.execute((ConnectionCallback<T>) connection -> {
                if (connection.getAutoCommit()) {
                    throw new IllegalStateException("Mapping work requires the owned workflow transaction.");
                }
                return work.doInConnection(connection);
            });
        } catch (DataAccessException failure) {
            throw new AuthorityUnavailableException();
        }
    }

    private static void requireOrganization(PolicyGuard guard, Target target) {
        if (!guard.organization().equals(target.organization())) {
            throw new AuthorityUnavailableException();
        }
    }
}
