package com.company.requests.adapter.jdbc;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.EffectiveScopeChange;
import com.company.requests.application.EffectiveScopeChange.Move;
import com.company.requests.application.EffectiveScopeChange.SetGrant;
import com.company.requests.application.EffectiveScopeChange.SetVisibility;
import com.company.requests.application.EffectiveScopeStore;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ScopeAuthorityChange.Entry;
import com.company.requests.application.ScopeAuthorityChange.State;
import com.company.requests.application.ScopeCheckpoint;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestRef;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Effective state on the same guarded PostgreSQL command connection as the witness and local effect. */
public final class JdbcEffectiveScopeStore implements EffectiveScopeStore {
    private final DataSource source;
    private final JdbcTemplate jdbc;

    public JdbcEffectiveScopeStore(DataSource source) {
        this.source = source;
        jdbc = new JdbcTemplate(source);
    }

    @Override
    public void requireProjection(PolicyGuard guard, ScopeCheckpoint checkpoint) {
        checkpoint.requireGuard(guard);
        var writer = checkpoint.admission().writer();
        guarded(guard, () -> {
            var matched = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM requests.policy_guard g
                        LEFT JOIN requests.scope_authority_projection p USING (organization)
                        WHERE g.organization = ? AND g.policy_version = ?
                          AND ((? = 0 AND p.organization IS NULL) OR
                               (p.writer_id = ? AND p.generation = ? AND p.sequence = ?
                                AND p.policy_version = g.policy_version AND p.prefix_digest = ?))
                    )
                    """, Boolean.class, guard.organization().value(), guard.policyVersion(),
                    checkpoint.sequence(), writer.writerId(), writer.generation(), checkpoint.sequence(),
                    checkpoint.prefixDigest());
            if (!Boolean.TRUE.equals(matched)) {
                throw new AuthorityUnavailableException();
            }
            return null;
        });
    }

    @Override
    public Optional<GrantOverride> grantOverride(PolicyGuard guard, GrantKey key) {
        requireScope(guard, key);
        return guarded(guard, () -> jdbc.query("""
                SELECT allowed, applied_version FROM requests.scope_grant_override
                WHERE organization = ? AND issuer = ? AND principal = ? AND permission = ? AND request_type = ?
                """, (row, index) -> {
                    long version = row.getLong(2);
                    if (version > guard.policyVersion()) {
                        throw new AuthorityUnavailableException();
                    }
                    return new GrantOverride(row.getBoolean(1), version);
                }, guard.organization().value(), key.actor().issuer(), key.actor().principal(),
                key.grant().name(), type(key)).stream().findFirst());
    }

    @Override
    public Optional<Assignment> assignment(PolicyGuard guard, RequestRef ref) {
        return guarded(guard, () -> jdbc.query("""
                SELECT assignee_person_id, assignment_version FROM requests.request_assignment
                WHERE organization = ? AND ref = ?
                """, (row, index) -> {
                    long version = row.getLong(2);
                    if (version > guard.policyVersion()) {
                        throw new AuthorityUnavailableException();
                    }
                    return new Assignment(new PersonId(row.getString(1)), version);
                }, guard.organization().value(), ref.value()).stream().findFirst());
    }

    @Override
    public Visibility visibility(PolicyGuard guard, RequestRef ref) {
        return guarded(guard, () -> jdbc.query("""
                SELECT fact_version, assignee_only, field_mask, applied_version FROM requests.request_visibility_fact
                WHERE organization = ? AND ref = ?
                """, (row, index) -> {
                    if (row.getLong(4) > guard.policyVersion()) {
                        throw new AuthorityUnavailableException();
                    }
                    return new Visibility(row.getLong(1), row.getBoolean(2), fields(row.getInt(3)));
                }, guard.organization().value(), ref.value()).stream().findFirst().orElse(Visibility.INITIAL));
    }

    @Override
    public void apply(PolicyGuard guard, Entry prepared, EffectiveScopeChange change) {
        JdbcTransactionRunner.requireExclusiveGuard(source, guard);
        var attempt = prepared.attempt();
        JdbcTransactionRunner.requireScopeAttempt(source, attempt);
        if (prepared.state() != State.PENDING || !guard.equals(change.before())
                || !guard.organization().equals(attempt.admission().genesis().organization())
                || guard.policyVersion() != attempt.beforeVersion()
                || !attempt.deltaDigest().equals(change.delta().digest())) {
            throw new AuthorityUnavailableException();
        }
        try {
            String organization = guard.organization().value();
            long version = attempt.afterVersion();
            int changed = switch (change.edit()) {
                case SetGrant update -> setGrant(guard, update, version);
                case Move move -> moveAssignment(organization, move);
                case SetVisibility visibility -> updateVisibility(organization, visibility, version);
            };
            if (changed != 1 || jdbc.update("""
                    UPDATE requests.policy_guard SET policy_version = ?
                    WHERE organization = ? AND policy_version = ?
                    """, version, organization, guard.policyVersion()) != 1) {
                throw new AuthorityUnavailableException();
            }
        } catch (DataAccessException failure) {
            throw new AuthorityUnavailableException();
        }
    }

    private int setGrant(PolicyGuard guard, SetGrant update, long version) {
        var key = update.key();
        requireScope(guard, key);
        String organization = guard.organization().value();
        if (update.prior().isEmpty()) {
            return jdbc.update("""
                    INSERT INTO requests.scope_grant_override
                        (organization, issuer, principal, permission, request_type, allowed, applied_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, organization, key.actor().issuer(), key.actor().principal(),
                    key.grant().name(), type(key), update.allowed(), version);
        }
        var prior = update.prior().orElseThrow();
        return jdbc.update("""
                UPDATE requests.scope_grant_override SET allowed = ?, applied_version = ?
                WHERE organization = ? AND issuer = ? AND principal = ? AND permission = ? AND request_type = ?
                  AND allowed = ? AND applied_version = ?
                """, update.allowed(), version, organization, key.actor().issuer(), key.actor().principal(),
                key.grant().name(), type(key), prior.allowed(), prior.version());
    }

    private int moveAssignment(String organization, Move move) {
        return jdbc.update("""
                UPDATE requests.request_assignment SET assignee_person_id = ?, assignment_version = ?
                WHERE organization = ? AND ref = ? AND assignee_person_id = ? AND assignment_version = ?
                """, move.next().person().value(), move.next().version(), organization, move.ref().value(),
                move.old().person().value(), move.old().version());
    }

    private int updateVisibility(String organization, SetVisibility change, long version) {
        var next = change.next();
        if (change.old().equals(Visibility.INITIAL)) {
            return jdbc.update("""
                    INSERT INTO requests.request_visibility_fact
                        (organization, ref, fact_version, assignee_only, field_mask, applied_version)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, organization, change.ref().value(), next.version(), next.assigneeOnly(), mask(next.fields()), version);
        }
        var old = change.old();
        return jdbc.update("""
                UPDATE requests.request_visibility_fact
                SET fact_version = ?, assignee_only = ?, field_mask = ?, applied_version = ?
                WHERE organization = ? AND ref = ? AND fact_version = ? AND assignee_only = ? AND field_mask = ?
                """, next.version(), next.assigneeOnly(), mask(next.fields()), version, organization, change.ref().value(),
                old.version(), old.assigneeOnly(), mask(old.fields()));
    }

    private <T> T guarded(PolicyGuard guard, Supplier<T> read) {
        JdbcTransactionRunner.requireGuard(source, guard);
        try {
            return read.get();
        } catch (DataAccessException failure) {
            throw new AuthorityUnavailableException();
        }
    }

    private static void requireScope(PolicyGuard guard, GrantKey key) {
        if (!guard.organization().equals(key.actor().organization())) {
            throw new AuthorityUnavailableException();
        }
    }

    private static String type(GrantKey key) {
        return key.type().map(Enum::name).orElse("");
    }

    private static int mask(Set<Field> fields) {
        int mask = 0;
        for (Field field : fields) {
            mask |= bit(field);
        }
        return mask;
    }

    private static Set<Field> fields(int mask) {
        var fields = EnumSet.noneOf(Field.class);
        for (Field field : Field.values()) {
            if ((mask & bit(field)) != 0) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static int bit(Field field) {
        return switch (field) {
            case REF -> 1;
            case ACTION -> 2;
            case RESULT_REVISION -> 4;
            case STATE -> 8;
            case NAME -> 16;
            case BODY -> 32;
        };
    }
}
