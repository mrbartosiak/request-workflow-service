package com.company.requests.application;

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

    public SyntheticScopePolicy(OrganizationId organization, long version, Collection<Grant> entries) {
        Map<Principal, Grant> byPrincipal = new HashMap<>();
        for (Grant grant : entries) {
            AuthenticatedActor actor = grant.actor();
            if (!actor.organization().equals(organization) || byPrincipal.putIfAbsent(Principal.of(actor), grant) != null) {
                throw new IllegalArgumentException("Grants must identify unique principals in this organization.");
            }
        }
        grants = Map.copyOf(byPrincipal);
        genesis = new ScopeAuthority.Genesis(organization, version, fingerprint(organization, version));
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
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
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
