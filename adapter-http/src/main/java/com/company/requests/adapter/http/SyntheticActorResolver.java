package com.company.requests.adapter.http;

import com.company.requests.application.ActorResolver;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.domain.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;

/** First-slice resolver for provisioned opaque synthetic credentials. */
public final class SyntheticActorResolver implements ActorResolver {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final String issuer;
    private final String audience;
    private final Map<String, DirectoryEntry> entries;

    public SyntheticActorResolver(String issuer, String audience, Collection<Binding> bindings) {
        this.issuer = requireText(issuer);
        this.audience = requireText(audience);
        Map<String, DirectoryEntry> byCredential = new HashMap<>();
        Map<OrganizationId, Map<String, AuthenticatedActor>> principals = new HashMap<>();
        for (Binding binding : bindings) {
            var actor = binding.actor();
            if (!issuer.equals(actor.issuer()) || !validToken(binding.secret())
                    || byCredential.putIfAbsent(digest(binding.secret()), new DirectoryEntry(binding.subject(), actor)) != null) {
                throw new IllegalArgumentException("Invalid or duplicate synthetic credential binding.");
            }
            var inScope = principals.computeIfAbsent(actor.organization(), ignored -> new HashMap<>());
            var prior = inScope.putIfAbsent(actor.principal(), actor);
            if (prior != null && !prior.equals(actor)) {
                throw new IllegalArgumentException("Aliases must agree on their stable actor binding.");
            }
        }
        entries = Map.copyOf(byCredential);
    }

    @Override
    public Optional<AuthenticatedActor> resolve(Credential credential, OrganizationId organization) {
        if (credential == null || !issuer.equals(credential.issuer()) || !audience.equals(credential.audience())) {
            return Optional.empty();
        }
        return lookup(credential.secret(), organization)
                .filter(entry -> entry.subject().equals(credential.subject()))
                .map(DirectoryEntry::actor);
    }

    /** The issuer, audience, subject and scope of a bearer are fixed by trusted provisioning. */
    public Optional<AuthenticatedActor> resolveBearer(HttpServletRequest request, OrganizationId organization) {
        var values = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (values == null || !values.hasMoreElements()) {
            return Optional.empty();
        }
        String header = values.nextElement();
        if (values.hasMoreElements() || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        return lookup(header.substring(7), organization).map(DirectoryEntry::actor);
    }

    private Optional<DirectoryEntry> lookup(String secret, OrganizationId organization) {
        if (!validToken(secret)) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(digest(secret)))
                .filter(entry -> entry.actor().organization().equals(organization));
    }

    private static boolean validToken(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return false;
        }
        byte[] bytes = Base64.getUrlDecoder().decode(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token);
    }

    private static String digest(String token) {
        try {
            var sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static String requireText(String value) {
        if (Objects.requireNonNull(value, "directory identifier").isBlank()) {
            throw new IllegalArgumentException("Directory identifiers must be nonblank.");
        }
        return value;
    }

    public record Binding(String subject, String secret, AuthenticatedActor actor) {
        public Binding {
            requireText(subject);
            Objects.requireNonNull(actor, "actor");
        }

        @Override
        public String toString() {
            return "Binding[protected]";
        }
    }

    private record DirectoryEntry(String subject, AuthenticatedActor actor) { }
}
