package com.company.requests.bootstrap;

import com.company.requests.adapter.integration.jdbc.JdbcInboundInbox;
import com.company.requests.adapter.jdbc.JdbcTransactionRunner;
import com.company.requests.adapter.jdbc.JdbcWorkflowRecords;
import com.company.requests.application.CurrentAccess;
import com.company.requests.application.EffectiveScopeStore.Field;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestState;
import com.company.requests.integration.ExternalMapping.Association;
import com.company.requests.integration.ExternalMappings;
import com.company.requests.integration.InboundCoordinator;
import com.company.requests.integration.SyntheticInboundSources;
import java.util.OptionalLong;
import java.util.Set;
import javax.sql.DataSource;

/** Opt-in local composition. Use the same workflow data source as current access and mapping projection. */
public final class SyntheticInboundConfiguration {
    private static final Set<Field> TARGET_FIELDS = Set.of(Field.REF, Field.RESULT_REVISION);

    private SyntheticInboundConfiguration() { }

    public static InboundCoordinator receiver(DataSource workflow, CurrentAccess access, ExternalMappings mappings,
                                              SyntheticInboundSources sources, InboundCoordinator.Continuity continuity) {
        var records = new JdbcWorkflowRecords(workflow);
        return new InboundCoordinator(sources, JdbcTransactionRunner.forCreate(workflow), records,
                access::requireDispatchScope, continuity,
                (actor, guard, key) -> mappings.verifiedUnderGuard(actor, guard, key).map(Association::target),
                (actor, guard, target) -> {
                    if (!access.permittedFields(actor, guard, target.ref()).containsAll(TARGET_FIELDS)) {
                        return OptionalLong.empty();
                    }
                    return records.find(guard.organization(), target.ref())
                            .filter(request -> request.type() == target.type() && request.state() != RequestState.DELETED)
                            .stream().mapToLong(Request::revision).findFirst();
                }, new JdbcInboundInbox(workflow));
    }
}
