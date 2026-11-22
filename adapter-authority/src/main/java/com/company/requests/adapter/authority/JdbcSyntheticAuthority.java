package com.company.requests.adapter.authority;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.IdentityAuthority;
import com.company.requests.application.ScopeAuthority;
import com.company.requests.domain.CreationOrder;
import com.company.requests.domain.RequestRef;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;

/** Independent PostgreSQL proof and durable Create reservation for the synthetic slice. */
public final class JdbcSyntheticAuthority implements ScopeAuthority, IdentityAuthority {
    private static final int REF_BYTES = 24;
    private static final int MAX_RANDOM_ATTEMPTS = 8;
    private final ProofTransactions transactions;
    private final Supplier<byte[]> randomBytes;

    public JdbcSyntheticAuthority(DataSource independentProofDataSource) {
        this(independentProofDataSource, secureSource());
    }

    JdbcSyntheticAuthority(DataSource independentProofDataSource, Supplier<byte[]> randomBytes) {
        transactions = new ProofTransactions(independentProofDataSource);
        this.randomBytes = Objects.requireNonNull(randomBytes, "random bytes");
    }

    @Override
    public void requireCurrent(Admission admission) {
        transactions.run(true, connection -> {
            requireCurrent(connection, admission);
            return null;
        });
    }

    @Override
    public Reservation reserve(Admission admission, UUID executionAttempt) {
        Objects.requireNonNull(executionAttempt, "execution attempt");
        return transactions.run(false, connection -> {
            requireCurrent(connection, admission);
            long epoch = admission.writer().generation();
            String organization = admission.genesis().organization().value();
            long priorOrdinal = lockCounter(connection, epoch);
            var previous = findAttempt(connection, executionAttempt, organization, epoch);
            if (previous != null) {
                return previous;
            }
            if (priorOrdinal == Long.MAX_VALUE) {
                throw new AuthorityUnavailableException();
            }
            long next = priorOrdinal + 1;
            for (int attempt = 0; attempt < MAX_RANDOM_ATTEMPTS; attempt++) {
                String ref = randomRef();
                if (insert(connection, executionAttempt, organization, ref, epoch, next)) {
                    advanceCounter(connection, epoch, next);
                    return reservation(ref, epoch, next);
                }
            }
            throw new AuthorityUnavailableException();
        });
    }

    private static void requireCurrent(Connection connection, Admission admission) throws SQLException {
        try (var proof = connection.prepareStatement("""
                SELECT 1 FROM request_authority.scope_genesis
                CROSS JOIN request_authority.writer_epoch
                WHERE organization = ? AND policy_version = ? AND policy_digest = ?
                AND writer_id = ? AND generation = ?
                """)) {
            proof.setString(1, admission.genesis().organization().value());
            proof.setLong(2, admission.genesis().policyVersion());
            proof.setString(3, admission.genesis().policyDigest());
            proof.setObject(4, admission.writer().writerId());
            proof.setLong(5, admission.writer().generation());
            try (var rows = proof.executeQuery()) {
                if (!rows.next()) {
                    throw new AuthorityUnavailableException();
                }
            }
        }
    }

    private static long lockCounter(Connection connection, long epoch) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT last_ordinal FROM request_authority.creation_counter WHERE generation = ? FOR UPDATE
                """)) {
            query.setLong(1, epoch);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new AuthorityUnavailableException();
                }
                return rows.getLong(1);
            }
        }
    }

    private static Reservation findAttempt(Connection connection, UUID attempt, String organization, long epoch)
            throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT organization, request_ref, created_epoch, created_ordinal
                FROM request_authority.creation_reservation WHERE attempt_id = ?
                """)) {
            query.setObject(1, attempt);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                if (!organization.equals(rows.getString("organization")) || epoch != rows.getLong("created_epoch")) {
                    throw new AuthorityUnavailableException();
                }
                return reservation(rows.getString("request_ref"), epoch, rows.getLong("created_ordinal"));
            }
        }
    }

    private static boolean insert(Connection connection, UUID attempt, String organization,
                                  String ref, long epoch, long ordinal) throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO request_authority.creation_reservation(
                    attempt_id, organization, request_ref, created_epoch, created_ordinal
                ) VALUES (?, ?, ?, ?, ?) ON CONFLICT (request_ref) DO NOTHING
                """)) {
            insert.setObject(1, attempt);
            insert.setString(2, organization);
            insert.setString(3, ref);
            insert.setLong(4, epoch);
            insert.setLong(5, ordinal);
            return insert.executeUpdate() == 1;
        }
    }

    private static void advanceCounter(Connection connection, long epoch, long ordinal) throws SQLException {
        try (var update = connection.prepareStatement("""
                UPDATE request_authority.creation_counter SET last_ordinal = ? WHERE generation = ?
                """)) {
            update.setLong(1, ordinal);
            update.setLong(2, epoch);
            if (update.executeUpdate() != 1) {
                throw new AuthorityUnavailableException();
            }
        }
    }

    private String randomRef() {
        byte[] bytes = randomBytes.get();
        if (bytes == null || bytes.length != REF_BYTES) {
            throw new AuthorityUnavailableException();
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Supplier<byte[]> secureSource() {
        SecureRandom random = new SecureRandom();
        return () -> {
            byte[] bytes = new byte[REF_BYTES];
            random.nextBytes(bytes);
            return bytes;
        };
    }

    private static Reservation reservation(String ref, long epoch, long ordinal) {
        return new Reservation(new RequestRef(ref), new CreationOrder(epoch, ordinal));
    }
}
