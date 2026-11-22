package com.company.requests.adapter.jdbc;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.application.ScopeAuthorityChange.Attempt;
import com.company.requests.application.ScopeAuthorityChange.Delta;
import com.company.requests.application.ScopeAuthorityChange.Entry;
import com.company.requests.application.ScopeAuthorityChange.LocalAttempt;
import com.company.requests.application.ScopeAuthorityChange.State;
import com.company.requests.application.ScopeAuthorityWitnessStore;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcScopeAuthorityWitnesses implements ScopeAuthorityWitnessStore {
    private final DataSource workflow;
    private final JdbcTemplate jdbc;
    private final JdbcWorkflowIdentity identity;

    public JdbcScopeAuthorityWitnesses(DataSource workflow) {
        this.workflow = workflow;
        jdbc = new JdbcTemplate(workflow);
        identity = new JdbcWorkflowIdentity(workflow);
    }

    @Override
    public LocalAttempt begin(Admission admission, PolicyGuard guard, Delta delta) {
        JdbcTransactionRunner.requireExclusiveGuard(workflow, guard);
        if (!guard.organization().equals(admission.genesis().organization()) || guard.policyVersion() == Long.MAX_VALUE) {
            throw new AuthorityUnavailableException();
        }
        var attempt = new Attempt(UUID.randomUUID(), admission, guard.policyVersion(), guard.policyVersion() + 1,
                delta.digest(), jdbc.queryForObject("SELECT pg_current_xact_id()::text", String.class));
        JdbcTransactionRunner.rememberScopeAttempt(workflow, attempt);
        return new LocalAttempt(attempt, identity.current());
    }

    @Override
    public void append(Entry prepared) {
        var attempt = prepared.attempt();
        JdbcTransactionRunner.requireScopeAttempt(workflow, attempt);
        if (prepared.state() != State.PENDING) {
            throw new AuthorityUnavailableException();
        }
        var writer = attempt.admission().writer();
        jdbc.update("""
                INSERT INTO requests.scope_authority_witness (
                    organization, sequence, attempt_id, writer_id, generation, before_version, after_version,
                    delta_digest, workflow_transaction
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, pg_current_xact_id())
                """, attempt.admission().genesis().organization().value(), prepared.sequence().orElseThrow(),
                attempt.id(), writer.writerId(), writer.generation(), attempt.beforeVersion(), attempt.afterVersion(),
                attempt.deltaDigest());
    }
}
