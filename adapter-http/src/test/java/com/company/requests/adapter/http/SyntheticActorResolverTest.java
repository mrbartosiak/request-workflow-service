package com.company.requests.adapter.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.requests.application.ActorResolver.Credential;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthenticatedActor.Kind;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

final class SyntheticActorResolverTest {
    private static final OrganizationId ORG = new OrganizationId("synthetic");
    private static final OrganizationId OTHER = new OrganizationId("other");
    private static final String ISSUER = "fixture-issuer";
    private static final String AUDIENCE = "requests";
    private static final String FIRST = token(1);
    private static final String ALIAS = token(2);
    private static final String SERVICE = token(3);
    private static final AuthenticatedActor HUMAN = new AuthenticatedActor(
            ISSUER, ORG, "stable-principal", Kind.HUMAN, Optional.of(new PersonId("stable-person")));
    private static final AuthenticatedActor ROBOT = new AuthenticatedActor(
            ISSUER, ORG, "service-principal", Kind.SERVICE, Optional.empty());
    private final SyntheticActorResolver resolver = new SyntheticActorResolver(ISSUER, AUDIENCE, List.of(
            new SyntheticActorResolver.Binding("first-login", FIRST, HUMAN),
            new SyntheticActorResolver.Binding("second-login", ALIAS, HUMAN),
            new SyntheticActorResolver.Binding("robot", SERVICE, ROBOT)));

    @Test
    void aliasesAndBearerResolveTheSameStablePerson() {
        assertEquals(Optional.of(HUMAN), resolver.resolve(new Credential(ISSUER, AUDIENCE, "first-login", FIRST), ORG));
        assertEquals(Optional.of(HUMAN), resolver.resolve(new Credential(ISSUER, AUDIENCE, "second-login", ALIAS), ORG));
        assertEquals(Optional.of(HUMAN), resolver.resolveBearer(request("bEaReR " + ALIAS), ORG));
        assertEquals(Optional.of(ROBOT), resolver.resolveBearer(request("Bearer " + SERVICE), ORG));
        assertTrue(ROBOT.person().isEmpty());
    }

    @Test
    void rejectsWrongIssuerAudienceOrganizationSubjectAndCredential() {
        for (var bad : List.of(new Credential("foreign", AUDIENCE, "first-login", FIRST),
                new Credential(ISSUER, "foreign", "first-login", FIRST),
                new Credential(ISSUER, AUDIENCE, "second-login", FIRST),
                new Credential(ISSUER, AUDIENCE, "first-login", token(8)))) {
            assertTrue(resolver.resolve(bad, ORG).isEmpty());
            assertFalse(bad.toString().contains(FIRST));
        }
        assertTrue(resolver.resolve(new Credential(ISSUER, AUDIENCE, "first-login", FIRST), OTHER).isEmpty());
        assertTrue(resolver.resolveBearer(request("Bearer " + FIRST), OTHER).isEmpty());
    }

    @Test
    void rejectsMissingDuplicateCombinedAndNoncanonicalAuthorization() {
        assertTrue(resolver.resolveBearer(new MockHttpServletRequest(), ORG).isEmpty());
        var duplicate = request("Bearer " + FIRST);
        duplicate.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + ALIAS);
        assertTrue(resolver.resolveBearer(duplicate, ORG).isEmpty());
        for (var bad : List.of("Bearer " + FIRST + ",Bearer " + ALIAS, "Basic " + FIRST,
                "Bearer  " + FIRST, "Bearer " + FIRST + " ", "Bearer " + FIRST + "=",
                "Bearer " + FIRST.substring(0, 42) + "B")) {
            assertTrue(resolver.resolveBearer(request(bad), ORG).isEmpty());
        }
    }

    @Test
    void rejectsAmbiguousDirectoryBindings() {
        var first = new SyntheticActorResolver.Binding("first-login", FIRST, HUMAN);
        assertThrows(IllegalArgumentException.class, () -> new SyntheticActorResolver(ISSUER, AUDIENCE,
                List.of(first, new SyntheticActorResolver.Binding("second-login", FIRST, HUMAN))));
        var differentPerson = new AuthenticatedActor(ISSUER, ORG, HUMAN.principal(),
                Kind.HUMAN, Optional.of(new PersonId("other-person")));
        assertThrows(IllegalArgumentException.class, () -> new SyntheticActorResolver(ISSUER, AUDIENCE,
                List.of(first, new SyntheticActorResolver.Binding("second-login", ALIAS, differentPerson))));
    }

    private static MockHttpServletRequest request(String header) {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, header);
        return request;
    }

    private static String token(int marker) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) marker;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
