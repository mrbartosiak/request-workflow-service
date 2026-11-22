package com.company.requests.adapter.integration.jdbc;

import com.company.requests.application.WriterLineage.Identity;
import com.company.requests.integration.ExternalMapping.Binding;
import com.company.requests.integration.ExternalMappings.OriginalEffect;
import java.time.OffsetDateTime;
import java.util.Objects;
import javax.sql.DataSource;

/** Reads an exact committed effect from the original certified workflow observer. */
public final class JdbcOriginalMappingEffect implements OriginalEffect {
    private final MappingProofTransactions original;
    private final JdbcExternalMappingRegistry registry;

    public JdbcOriginalMappingEffect(DataSource originalWorkflowObserver, JdbcExternalMappingRegistry registry) {
        original = new MappingProofTransactions(originalWorkflowObserver);
        this.registry = Objects.requireNonNull(registry);
    }

    @Override
    public boolean committed(Binding binding) {
        return registry.originalLineage(binding).filter(lineage -> original.run(true, connection -> {
            try (var query = connection.createStatement();
                 var rows = query.executeQuery("""
                        SELECT host(inet_server_addr()), inet_server_port(), current_database(), pg_postmaster_start_time()
                        WHERE NOT pg_is_in_recovery()
                        """)) {
                if (!rows.next() || rows.getString(1) == null || !lineage.equals(new Identity(
                        rows.getString(1), rows.getInt(2), rows.getString(3),
                        rows.getObject(4, OffsetDateTime.class).toInstant()))) {
                    return false;
                }
            }
            try (var query = connection.prepareStatement("SELECT " + JdbcMappingRows.COLUMNS + """
                    FROM requests.external_mapping_commit_evidence WHERE
                    """ + JdbcMappingRows.KEY)) {
                JdbcMappingRows.find(query, binding.key());
                try (var rows = query.executeQuery()) {
                    return JdbcMappingRows.one(rows, binding.key()).filter(binding::equals).isPresent();
                }
            }
        })).isPresent();
    }
}
