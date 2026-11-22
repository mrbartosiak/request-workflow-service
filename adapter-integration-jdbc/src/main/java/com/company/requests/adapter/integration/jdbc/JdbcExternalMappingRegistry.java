package com.company.requests.adapter.integration.jdbc;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.WriterEpoch;
import com.company.requests.application.WriterLineage.Identity;
import com.company.requests.integration.ExternalMapping.Association;
import com.company.requests.integration.ExternalMapping.Binding;
import com.company.requests.integration.ExternalMapping.Key;
import com.company.requests.integration.ExternalMappings.Registry;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Independently durable registry on an authority connection, outside workflow rewind. */
public final class JdbcExternalMappingRegistry implements Registry {
    private final MappingProofTransactions proof;

    public JdbcExternalMappingRegistry(DataSource independentRegistry) {
        proof = new MappingProofTransactions(independentRegistry);
    }

    @Override
    public Reservation reserve(Key key, Association association, WriterEpoch writer) {
        return proof.run(false, connection -> {
            try (var reserve = connection.prepareStatement("""
                    SELECT * FROM request_authority.reserve_external_mapping(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                JdbcMappingRows.write(reserve, UUID.randomUUID(), key, association, writer);
                try (var rows = reserve.executeQuery()) {
                    if (!rows.next()) {
                        throw new AuthorityUnavailableException();
                    }
                    var state = Reservation.State.valueOf(rows.getString("outcome"));
                    if (state == Reservation.State.QUARANTINED) {
                        return new Reservation(state, Optional.empty());
                    }
                    var original = new WriterEpoch(rows.getObject("original_writer", UUID.class),
                            rows.getLong("original_generation"));
                    var binding = new Binding(rows.getObject("id", UUID.class), key, association, original);
                    return new Reservation(state, Optional.of(binding));
                }
            }
        });
    }

    @Override
    public Optional<Binding> find(Key key) {
        return proof.run(true, connection -> {
            try (var find = connection.prepareStatement("SELECT " + JdbcMappingRows.COLUMNS + """
                    FROM request_authority.external_mapping_binding b WHERE
                    """ + JdbcMappingRows.KEY + """
                    AND NOT EXISTS (SELECT 1 FROM request_authority.external_mapping_quarantine q
                                   WHERE q.binding_id = b.binding_id)
                    """)) {
                JdbcMappingRows.find(find, key);
                try (var rows = find.executeQuery()) {
                    return JdbcMappingRows.one(rows, key);
                }
            }
        });
    }

    Optional<Identity> originalLineage(Binding binding) {
        return proof.run(true, connection -> {
            try (var find = connection.prepareStatement("""
                    SELECT server_address, server_port, database_name, server_started_at
                    FROM request_authority.external_mapping_binding b
                    WHERE binding_id = ? AND writer_id = ? AND generation = ?
                    AND NOT EXISTS (SELECT 1 FROM request_authority.external_mapping_quarantine q
                                   WHERE q.binding_id = b.binding_id)
                    """)) {
                find.setObject(1, binding.id());
                find.setObject(2, binding.writer().writerId());
                find.setLong(3, binding.writer().generation());
                try (var rows = find.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Identity(rows.getString(1), rows.getInt(2), rows.getString(3),
                            rows.getObject(4, OffsetDateTime.class).toInstant()));
                }
            }
        });
    }
}
