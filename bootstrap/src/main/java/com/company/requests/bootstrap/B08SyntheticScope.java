package com.company.requests.bootstrap;

import com.company.requests.adapter.http.SyntheticActorResolver;
import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.AuthenticatedActor.Kind;
import com.company.requests.application.SyntheticScopePolicy;
import com.company.requests.application.SyntheticScopePolicy.Grant;
import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PersonId;
import com.company.requests.domain.RequestType;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Immutable scope shared by the B08 proof provisioner and listening application. */
public final class B08SyntheticScope {
    public static final OrganizationId ORGANIZATION = new OrganizationId("b08-workflow-scope");
    public static final String ISSUER = "b08-synthetic-issuer";
    public static final String AUDIENCE = "b08-synthetic-audience";
    public static final RequestType VISIBLE = RequestType.HR_TICKET;
    public static final RequestType CONCEALED = RequestType.BUG_REPORT;
    public static final AuthenticatedActor OWNER = human("owner");
    private static final AuthenticatedActor KEY_ONLY = human("key-only");
    private static final AuthenticatedActor NO_ISSUE = human("no-issue");
    public static final SyntheticScopePolicy POLICY = new SyntheticScopePolicy(ORGANIZATION, 1, List.of(
            new Grant(OWNER, true, Set.of(VISIBLE, CONCEALED), Set.of(VISIBLE)),
            new Grant(KEY_ONLY, true, Set.of(), Set.of()),
            new Grant(NO_ISSUE, false, Set.of(VISIBLE), Set.of(VISIBLE))));

    private B08SyntheticScope() { }

    public static SyntheticActorResolver actors(String owner, String alias, String keyOnly, String noIssue) {
        return new SyntheticActorResolver(ISSUER, AUDIENCE, List.of(
                new SyntheticActorResolver.Binding("owner", owner, OWNER),
                new SyntheticActorResolver.Binding("owner-alias", alias, OWNER),
                new SyntheticActorResolver.Binding("limited", keyOnly, KEY_ONLY),
                new SyntheticActorResolver.Binding("no-issue", noIssue, NO_ISSUE)));
    }

    private static AuthenticatedActor human(String principal) {
        return new AuthenticatedActor(ISSUER, ORGANIZATION, principal, Kind.HUMAN,
                Optional.of(new PersonId("person-" + principal)));
    }
}
