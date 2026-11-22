package com.company.requests.adapter.jdbc;

import com.company.requests.application.AuthorityUnavailableException;
import com.company.requests.application.PolicyGuard;
import com.company.requests.application.ScopeCheckpoint;
import com.company.requests.application.ScopeProjectionStore;
import com.company.requests.application.WriterLineage.Identity;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Replaces the confirmed watermark while the bound organization guard excludes effects and releases. */
public final class JdbcScopeProjectionStore implements ScopeProjectionStore {
    private final DataSource workflow;
    private final JdbcWorkflowIdentity identity;
    private final JdbcTemplate jdbc;

    public JdbcScopeProjectionStore(DataSource workflow) {
        this.workflow = workflow;
        identity = new JdbcWorkflowIdentity(workflow);
        jdbc = new JdbcTemplate(workflow);
    }

    @Override
    public Identity writer(PolicyGuard guard) {
        JdbcTransactionRunner.requireExclusiveGuard(workflow, guard);
        return identity.current();
    }

    @Override
    public void replace(PolicyGuard guard, ScopeCheckpoint checkpoint) {
        JdbcTransactionRunner.requireExclusiveGuard(workflow, guard);
        checkpoint.requireGuard(guard);
        var admission = checkpoint.admission();
        if (jdbc.update("""
                INSERT INTO requests.scope_authority_projection (
                    organization, writer_id, generation, sequence, policy_version, prefix_digest
                )
                SELECT organization, ?, ?, ?, policy_version, ? FROM requests.policy_guard
                WHERE organization = ? AND policy_version = ?
                ON CONFLICT (organization) DO UPDATE SET
                    writer_id = excluded.writer_id, generation = excluded.generation, sequence = excluded.sequence,
                    policy_version = excluded.policy_version, prefix_digest = excluded.prefix_digest
                WHERE requests.scope_authority_projection.sequence <= excluded.sequence
                """, admission.writer().writerId(), admission.writer().generation(), checkpoint.sequence(),
                checkpoint.prefixDigest(), guard.organization().value(), guard.policyVersion()) != 1) {
            throw new AuthorityUnavailableException();
        }
    }
}
