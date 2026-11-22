package com.company.requests.adapter.authority;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.OperationReceipt;
import com.company.requests.application.ReleaseEvidence;
import com.company.requests.application.ReleaseEvidence.ClientReceipt;
import com.company.requests.application.ReleaseEvidence.Correlation;
import com.company.requests.application.ReleaseEvidence.Handoff;
import com.company.requests.application.ReleaseEvidence.PreparedStatus;
import com.company.requests.application.ReleasePreparation;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PublicationId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Private, separately authorized ports on the independent release evidence journal. */
public final class JdbcReleaseEvidence {
    private JdbcReleaseEvidence() { }

    public enum Input { PREPARATION, HANDOFF, CLIENT, READ }

    @FunctionalInterface
    public interface Reader {
        Optional<PreparedStatus> find(OrganizationId organization, UUID correlationId);
    }

    public static ReleasePreparation preparation(DataSource source) {
        var transactions = new ProofTransactions(source);
        return new ReleasePreparation() {
            @Override
            public void append(Admission admission, OperationReceipt receipt) {
                prepare(admission, receipt);
            }

            @Override
            public Optional<Correlation> prepare(Admission admission, OperationReceipt receipt) {
                var correlation = new Correlation(UUID.randomUUID(), receipt.key().organization(), resultDigest(receipt));
                if (!admission.genesis().organization().equals(correlation.organization())
                        || receipt.keyGeneration() > admission.writer().generation()) {
                    throw new AuthorityUnavailableException();
                }
                transactions.run(false, connection -> {
                    try (var statement = connection.prepareStatement("""
                            SELECT request_authority.prepare_release(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)) {
                        bindCorrelation(statement, correlation);
                        statement.setString(4, receipt.resultRef().value());
                        statement.setLong(5, receipt.resultRevision());
                        statement.setLong(6, receipt.keyGeneration());
                        statement.setLong(7, admission.writer().generation());
                        statement.setObject(8, admission.writer().writerId());
                        statement.setLong(9, admission.genesis().policyVersion());
                        statement.setString(10, admission.genesis().policyDigest());
                        requireRecorded(statement);
                    }
                    return null;
                });
                return Optional.of(correlation);
            }
        };
    }

    public static ReleaseEvidence.Handoffs handoffs(DataSource source) {
        var transactions = new ProofTransactions(source);
        return correlation -> record(transactions, correlation, Input.HANDOFF);
    }

    public static ReleaseEvidence.Acknowledgments acknowledgments(DataSource source) {
        var transactions = new ProofTransactions(source);
        return correlation -> record(transactions, correlation, Input.CLIENT);
    }

    public static Reader reader(DataSource source) {
        var transactions = new ProofTransactions(source);
        return (organization, correlationId) -> transactions.run(true, connection -> {
            try (var query = connection.prepareStatement("SELECT * FROM request_authority.read_release_evidence(?, ?)")) {
                query.setObject(1, correlationId);
                query.setString(2, organization.value());
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    var status = new PreparedStatus(rows.getBoolean(1) ? Handoff.PROVED : Handoff.UNKNOWN,
                            rows.getBoolean(2) ? ClientReceipt.CONFIRMED : ClientReceipt.UNKNOWN);
                    if (rows.next()) {
                        throw new AuthorityUnavailableException();
                    }
                    return Optional.of(status);
                }
            }
        });
    }

    private static void record(ProofTransactions transactions, Correlation correlation, Input input) {
        transactions.run(false, connection -> {
            try (var statement = connection.prepareStatement("SELECT request_authority.record_release_evidence(?, ?, ?, ?)")) {
                bindCorrelation(statement, correlation);
                statement.setString(4, input.name());
                requireRecorded(statement);
            }
            return null;
        });
    }

    private static void requireRecorded(PreparedStatement statement) throws SQLException {
        try (var rows = statement.executeQuery()) {
            if (!rows.next() || !rows.getBoolean(1)) {
                throw new AuthorityUnavailableException();
            }
        }
    }

    private static void bindCorrelation(PreparedStatement statement, Correlation correlation) throws SQLException {
        statement.setObject(1, correlation.id());
        statement.setString(2, correlation.organization().value());
        statement.setBytes(3, HexFormat.of().parseHex(correlation.resultDigest()));
    }

    private static String resultDigest(OperationReceipt receipt) {
        var key = receipt.key();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[]{
                    "requests.release-result.v1", key.issuer(), key.organization().value(), key.principal(), key.tokenId(),
                    receipt.action().name(), Long.toString(receipt.keyGeneration()), receipt.expiresAt().toString(),
                    Integer.toString(receipt.intentDigest().version()), receipt.intentDigest().hex(), receipt.resultRef().value(),
                    Long.toString(receipt.resultRevision()), receipt.resultState().name(),
                    receipt.publicationId().map(PublicationId::value).map(Object::toString).orElse(""),
                    Integer.toString(receipt.originalStatus())}) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
