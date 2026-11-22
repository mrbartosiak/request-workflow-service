package com.company.requests.application;

import com.company.requests.application.EffectiveScopeUpdates.Permission;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, scope-local directory bindings and explicit grants for the synthetic first slice. */
public final class SyntheticScopePolicy {
    private final ScopeAuthority.Genesis genesis;
    private final Map<Principal, Grant> grants;
    private final Map<Principal, Set<Permission>> mutationOperators;

    public SyntheticScopePolicy(OrganizationId organization, long version, Collection<Grant> entries) {
        Map<Principal, Grant> byPrincipal = new HashMap<>();
        for (Grant grant : entries) {
            AuthenticatedActor actor = grant.actor();
            if (!actor.organization().equals(organization) || byPrincipal.putIfAbsent(Principal.of(actor), grant) != null) {
                throw new IllegalArgumentException("Grants must identify unique principals in this organization.");
            }
        }
        grants = Map.copyOf(byPrincipal);
        mutationOperators = null;
        genesis = new ScopeAuthority.Genesis(organization, version, fingerprint(organization, version));
    }

    private SyntheticScopePolicy(ScopeAuthority.Genesis genesis, Map<Principal, Grant> grants,
                                 Map<Principal, Set<Permission>> mutationOperators) {
        this.genesis = genesis;
        this.grants = grants;
        this.mutationOperators = mutationOperators;
    }

    /** Certifies the immutable B10 operator rights alongside the ordinary directory and grants. */
    public SyntheticScopePolicy withMutationOperators(Map<AuthenticatedActor, Set<Permission>> operators) {
        if (mutationOperators != null) {
            throw new IllegalStateException("Scope mutation operators are already bound.");
        }
        Map<Principal, Set<Permission>> bound = new HashMap<>();
        operators.forEach((actor, permissions) -> {
            if (actor.person().isEmpty() || !genesis.organization().equals(actor.organization())) {
                throw new IllegalArgumentException("Scope operators require an authenticated local human binding.");
            }
            grant(actor);
            var exact = Set.copyOf(permissions);
            if (!exact.isEmpty()) {
                bound.put(Principal.of(actor), exact);
            }
        });
        var digest = sha256();
        add(digest, "requests.synthetic-scope.effective-operators.v1");
        add(digest, genesis.policyDigest());
        bound.entrySet().stream().sorted(Map.Entry.comparingByKey(Principal.ORDER)).forEach(entry -> {
            add(digest, entry.getKey().issuer());
            add(digest, entry.getKey().id());
            for (Permission permission : Permission.values()) {
                add(digest, permission.name());
                add(digest, Boolean.toString(entry.getValue().contains(permission)));
            }
        });
        var effective = new ScopeAuthority.Genesis(genesis.organization(), genesis.policyVersion(),
                HexFormat.of().formatHex(digest.digest()));
        return new SyntheticScopePolicy(effective, grants, Map.copyOf(bound));
    }

    boolean permitsMutation(AuthenticatedActor actor, Permission permission) {
        if (mutationOperators == null) {
            return false;
        }
        var principal = Principal.of(actor);
        var granted = grants.get(principal);
        return granted != null && granted.actor().equals(actor)
                && mutationOperators.getOrDefault(principal, Set.of()).contains(permission);
    }

    boolean permits(EffectiveScopeStore.GrantKey key) {
        Grant configured = grant(key.actor());
        return switch (key.grant()) {
            case KEY_ISSUE -> configured.keyIssue();
            case CREATE -> configured.create().contains(key.type().orElseThrow());
            case CREATE_RESULT -> configured.createResult().contains(key.type().orElseThrow());
            default -> false; // Existing-request grants start closed and require an ordered local enablement.
        };
    }

    public ScopeAuthority.Genesis genesis() {
        return genesis;
    }

    Grant grant(AuthenticatedActor actor) {
        Grant grant = grants.get(Principal.of(actor));
        if (grant == null || !grant.actor().equals(actor)) {
            throw new AccessDeniedException();
        }
        return grant;
    }

    public record Grant(AuthenticatedActor actor, boolean keyIssue, Set<RequestType> create,
                        Set<RequestType> createResult) {
        public Grant {
            Objects.requireNonNull(actor, "actor");
            create = Set.copyOf(create);
            createResult = Set.copyOf(createResult);
        }
    }

    private String fingerprint(OrganizationId organization, long version) {
        var digest = sha256();
        add(digest, "requests.synthetic-scope.genesis.v1");
        add(digest, organization.value());
        add(digest, Long.toString(version));
        grants.entrySet().stream().sorted(Map.Entry.comparingByKey(Principal.ORDER))
                .map(Map.Entry::getValue).forEach(grant -> {
                    var actor = grant.actor();
                    add(digest, actor.issuer());
                    add(digest, actor.principal());
                    add(digest, actor.kind().name());
                    add(digest, actor.person().map(PersonId::value).orElse(""));
                    add(digest, Boolean.toString(grant.keyIssue()));
                    for (RequestType type : RequestType.values()) {
                        add(digest, type.name());
                        add(digest, Boolean.toString(grant.create().contains(type)));
                        add(digest, Boolean.toString(grant.createResult().contains(type)));
                    }
                });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static void add(MessageDigest digest, String value) {
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value) || value.indexOf(0) >= 0) {
            throw new IllegalArgumentException("Policy fields require exact UTF-8 text.");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private record Principal(String issuer, String id) {
        private static final Comparator<Principal> ORDER =
                Comparator.comparing(Principal::issuer).thenComparing(Principal::id);

        static Principal of(AuthenticatedActor actor) {
            return new Principal(actor.issuer(), actor.principal());
        }
    }
}
