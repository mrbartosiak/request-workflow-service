package com.company.requests.adapter.authority;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.OperationReceipt;
import com.company.requests.application.ReceiptKey;
import com.company.requests.application.ReleasePreparation;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.WriterEpoch;
import com.company.requests.application.WriterLineage.Identity;
import com.company.requests.domain.Action;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;

/** Immutable writer certificate and write-only preparation evidence outside the workflow database. */
public final class JdbcCreateProof implements ReleasePreparation {
    private final ProofTransactions transactions;

    public JdbcCreateProof(DataSource independentProofDataSource) {
        transactions = new ProofTransactions(independentProofDataSource);
    }

    public void requireWriter(Admission admission, Identity identity) {
        transactions.run(true, connection -> {
            JdbcSyntheticAuthority.requireCurrent(connection, admission);
            requireWorkflow(connection, admission.writer(), identity);
            return null;
        });
    }

    @Override
    public void append(Admission admission, OperationReceipt receipt) {
        var organization = admission.genesis().organization();
        long generation = admission.writer().generation();
        if (receipt.action() != Action.CREATE || !receipt.key().organization().equals(organization)
                || receipt.keyGeneration() != generation) {
            throw new AuthorityUnavailableException();
        }
        transactions.run(false, connection -> {
            JdbcSyntheticAuthority.requireCurrent(connection, admission);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO request_authority.release_prepared (
                        correlation_id, organization, key_digest, result_ref, result_revision, generation
                    ) SELECT ?, organization, ?, request_ref, ?, created_epoch
                    FROM request_authority.creation_reservation
                    WHERE organization = ? AND request_ref = ? AND created_epoch = ?
                    """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setBytes(2, digest(receipt.key()));
                insert.setLong(3, receipt.resultRevision());
                insert.setString(4, organization.value());
                insert.setString(5, receipt.resultRef().value());
                insert.setLong(6, generation);
                if (insert.executeUpdate() != 1) {
                    throw new AuthorityUnavailableException();
                }
            }
            return null;
        });
    }

    static void requireWorkflow(Connection connection, WriterEpoch writer, Identity identity) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT 1 FROM request_authority.workflow_certificate JOIN request_authority.writer_epoch USING (generation)
                WHERE writer_id = ? AND generation = ? AND server_address = ? AND server_port = ?
                    AND database_name = ? AND server_started_at = ?
                """)) {
            query.setObject(1, writer.writerId());
            query.setLong(2, writer.generation());
            bindIdentity(query, 3, identity);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new AuthorityUnavailableException();
                }
            }
        }
    }

    static void bindIdentity(PreparedStatement statement, int first, Identity identity) throws SQLException {
        statement.setString(first, identity.address());
        statement.setInt(first + 1, identity.port());
        statement.setString(first + 2, identity.database());
        statement.setObject(first + 3, identity.startedAt().atOffset(ZoneOffset.UTC));
    }

    private static byte[] digest(ReceiptKey key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[]{"requests.create.release-key.v1", key.issuer(),
                    key.organization().value(), key.principal(), key.tokenId()}) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
