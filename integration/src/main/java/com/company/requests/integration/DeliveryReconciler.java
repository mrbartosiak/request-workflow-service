package com.company.requests.integration;

import com.company.requests.application.AuthenticatedActor;
import com.company.requests.application.WriterEpoch;
import com.company.requests.integration.DispatchStore.AttemptId;
import com.company.requests.integration.SyntheticDelivery.Action;
import com.company.requests.integration.SyntheticDelivery.Connector;
import com.company.requests.integration.SyntheticDelivery.Evidence;
import com.company.requests.integration.SyntheticDelivery.Store;
import java.util.Objects;
import java.util.Optional;

/** Trusted synthetic recovery entry point. The caller supplies the authenticated operator binding. */
public final class DeliveryReconciler {
    public enum Result { RECORDED, WITHHELD }

    private final Store store;
    private final WriterEpoch writer;
    private final Connector connector;

    public DeliveryReconciler(Store store, WriterEpoch writer, Connector connector) {
        this.store = Objects.requireNonNull(store);
        this.writer = Objects.requireNonNull(writer);
        this.connector = Objects.requireNonNull(connector);
    }

    public Result recover(AuthenticatedActor operator, AttemptId attempt, Action action) {
        var recovery = store.startRecovery(writer, operator, attempt, action);
        if (recovery.isEmpty()) {
            return Result.WITHHELD;
        }
        var admitted = recovery.orElseThrow();
        Optional<Evidence> evidence;
        try {
            boolean fence = action == Action.FENCE_AND_RETRY || action == Action.FENCE_AND_SUPPRESS;
            evidence = SyntheticDelivery.exact(admitted.invocation(), connector.lookup(admitted.invocation(), fence));
        } catch (RuntimeException unavailable) {
            evidence = Optional.empty();
        }
        return store.finishRecovery(admitted, evidence)
                ? Result.RECORDED : Result.WITHHELD;
    }
}
