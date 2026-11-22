package com.company.requests.adapter.integration.jdbc;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.WriterEpoch;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import com.company.requests.integration.ExternalMapping.Association;
import com.company.requests.integration.ExternalMapping.Binding;
import com.company.requests.integration.ExternalMapping.Key;
import com.company.requests.integration.ExternalMapping.Publication;
import com.company.requests.integration.ExternalMapping.Target;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;

final class JdbcMappingRows {
    static final String KEY = """
            external_id_digest = sha256(?) AND external_id = ? AND source_system = ? AND source_tenant = ?
            AND identifier_type = ?
            """;
    static final String COLUMNS = """
            binding_id, source_system, source_tenant, identifier_type, external_id, organization,
            request_type, ref, created_epoch, created_ordinal, publication_namespace, publication_id,
            writer_id, generation
            """;

    private JdbcMappingRows() { }

    static void find(PreparedStatement statement, Key key) throws SQLException {
        byte[] bytes = key.externalId();
        statement.setBytes(1, bytes);
        statement.setBytes(2, bytes);
        statement.setString(3, key.scope().sourceSystem());
        statement.setString(4, key.scope().tenant());
        statement.setString(5, key.scope().identifierType());
    }

    static void write(PreparedStatement statement, UUID id, Key key, Association value, WriterEpoch writer)
            throws SQLException {
        statement.setObject(1, id);
        statement.setString(2, key.scope().sourceSystem());
        statement.setString(3, key.scope().tenant());
        statement.setString(4, key.scope().identifierType());
        statement.setBytes(5, key.externalId());
        statement.setString(6, value.target().organization().value());
        statement.setString(7, value.target().type().name());
        statement.setString(8, value.target().ref().value());
        statement.setLong(9, value.creation().epoch());
        statement.setLong(10, value.creation().ordinal());
        if (value.publication().isPresent()) {
            var publication = value.publication().orElseThrow();
            statement.setString(11, publication.namespace());
            statement.setLong(12, publication.id().value());
        } else {
            statement.setNull(11, Types.VARCHAR);
            statement.setNull(12, Types.BIGINT);
        }
        statement.setObject(13, writer.writerId());
        statement.setLong(14, writer.generation());
    }

    static Optional<Binding> one(ResultSet rows, Key key) throws SQLException {
        if (!rows.next()) {
            return Optional.empty();
        }
        var target = new Target(new OrganizationId(rows.getString("organization")),
                RequestType.valueOf(rows.getString("request_type")), new RequestRef(rows.getString("ref")));
        var order = new CreationOrder(rows.getLong("created_epoch"), rows.getLong("created_ordinal"));
        var publication = Optional.ofNullable(rows.getObject("publication_id", Long.class))
                .map(PublicationId::new);
        String namespace = rows.getString("publication_namespace");
        var association = new Association(target, order, publication.map(id -> new Publication(namespace, id)));
        var writer = new WriterEpoch(rows.getObject("writer_id", UUID.class), rows.getLong("generation"));
        var binding = new Binding(rows.getObject("binding_id", UUID.class), key, association, writer);
        if (rows.next()) {
            throw new AuthorityUnavailableException();
        }
        return Optional.of(binding);
    }
}
