package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.RequestType;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Proof and grant checks for the immutable synthetic policy. */
public final class CurrentAccess {
    private final ScopeAuthority authority;
    private final WriterEpoch writer;
    private final Map<OrganizationId, SyntheticScopePolicy> policies;

    public CurrentAccess(ScopeAuthority authority, WriterEpoch writer, Collection<SyntheticScopePolicy> policies) {
        this.authority = Objects.requireNonNull(authority, "scope authority");
        this.writer = Objects.requireNonNull(writer, "writer epoch");
        Map<OrganizationId, SyntheticScopePolicy> scopes = new HashMap<>();
        for (SyntheticScopePolicy policy : policies) {
            if (scopes.putIfAbsent(policy.genesis().organization(), policy) != null) {
                throw new IllegalArgumentException("An organization requires one synthetic policy.");
            }
        }
        this.policies = Map.copyOf(scopes);
    }

    public ScopeAuthority.Admission requireKeyIssue(AuthenticatedActor actor) {
        var policy = policy(actor);
        var admission = requireProof(policy);
        if (!policy.grant(actor).keyIssue()) {
            throw new AccessDeniedException();
        }
        return admission;
    }

    /** Preflight before receipt arbitration or lookup. */
    public ScopeAuthority.Admission requireCreate(AuthenticatedActor actor, RequestType type) {
        return requireCreate(actor, type, policy(actor), null);
    }

    /** Recheck inside the command or release guard. */
    public ScopeAuthority.Admission requireCreate(AuthenticatedActor actor, RequestType type, PolicyGuard guard) {
        return requireCreate(actor, type, policy(actor), Objects.requireNonNull(guard, "organization guard"));
    }

    private ScopeAuthority.Admission requireCreate(AuthenticatedActor actor, RequestType type,
                                                   SyntheticScopePolicy policy, PolicyGuard guard) {
        var admission = requireProof(policy);
        if (guard != null && !matches(guard, policy.genesis())) {
            throw new AuthorityUnavailableException();
        }
        if (actor.kind() != AuthenticatedActor.Kind.HUMAN || !policy.grant(actor).create().contains(type)) {
            throw new AccessDeniedException();
        }
        return admission;
    }

    boolean permitsCreateResult(AuthenticatedActor actor, RequestType originalType) {
        return policy(actor).grant(actor).createResult().contains(originalType);
    }

    private SyntheticScopePolicy policy(AuthenticatedActor actor) {
        var policy = policies.get(actor.organization());
        if (policy == null) {
            throw new AuthorityUnavailableException();
        }
        return policy;
    }

    private ScopeAuthority.Admission requireProof(SyntheticScopePolicy policy) {
        var admission = new ScopeAuthority.Admission(policy.genesis(), writer);
        authority.requireCurrent(admission);
        return admission;
    }

    private static boolean matches(PolicyGuard guard, ScopeAuthority.Genesis genesis) {
        return guard.organization().equals(genesis.organization()) && guard.policyVersion() == genesis.policyVersion();
    }
}
