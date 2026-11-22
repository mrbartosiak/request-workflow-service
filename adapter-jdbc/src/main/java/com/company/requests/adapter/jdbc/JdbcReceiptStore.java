package com.company.requests.adapter.jdbc;

import com.company.requests.application.OperationReceipt;
import com.company.requests.application.ReceiptIntentDigest;
import com.company.requests.application.ReceiptKey;
import com.company.requests.application.ReceiptStore;
import com.company.requests.domain.Action;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestState;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcReceiptStore implements ReceiptStore {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public JdbcReceiptStore(DataSource commandDataSource) {
        dataSource = commandDataSource;
        jdbc = new JdbcTemplate(commandDataSource);
    }

    @Override
    public void insert(OperationReceipt receipt) {
        JdbcTransactionRunner.requireBoundConnection(dataSource);
        ReceiptKey key = receipt.key();
        jdbc.update("""
                INSERT INTO requests.operation_receipt (
                    issuer, organization, principal, token_id, intent_digest_version, intent_digest,
                    key_generation, expires_epoch_second, expires_nano, result_action, result_ref,
                    result_revision, result_state, publication_namespace, publication_id, original_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, key.issuer(), key.organization().value(), key.principal(), key.tokenId(),
                receipt.intentDigest().version(), HexFormat.of().parseHex(receipt.intentDigest().hex()),
                receipt.keyGeneration(), receipt.expiresAt().getEpochSecond(), receipt.expiresAt().getNano(),
                receipt.action().name(), receipt.resultRef().value(), receipt.resultRevision(), receipt.resultState().name(),
                receipt.publicationId().map(id -> PublicationId.INITIAL_NAMESPACE).orElse(null),
                receipt.publicationId().map(PublicationId::value).orElse(null), receipt.originalStatus());
    }

    @Override
    public Optional<OperationReceipt> find(ReceiptKey key) {
        JdbcTransactionRunner.requireBoundConnection(dataSource);
        return jdbc.query("""
                SELECT intent_digest_version, intent_digest, key_generation, expires_epoch_second, expires_nano,
                       result_action, result_ref, result_revision, result_state, publication_id, original_status
                FROM requests.operation_receipt WHERE issuer = ? AND organization = ? AND principal = ? AND token_id = ?
                """, (row, index) -> new OperationReceipt(key,
                new ReceiptIntentDigest(row.getInt("intent_digest_version"), HexFormat.of().formatHex(row.getBytes("intent_digest"))),
                row.getLong("key_generation"), Instant.ofEpochSecond(row.getLong("expires_epoch_second"), row.getInt("expires_nano")),
                Action.valueOf(row.getString("result_action")), new RequestRef(row.getString("result_ref")),
                row.getLong("result_revision"), RequestState.valueOf(row.getString("result_state")),
                JdbcWorkflowRecords.publicationId(row), row.getInt("original_status")),
                key.issuer(), key.organization().value(), key.principal(), key.tokenId()).stream().findFirst();
    }
}
