package com.company.requests.application;

import com.company.requests.application.PublicationJournal.Entry;
import com.company.requests.application.PublicationJournal.State;
import com.company.requests.application.ScopeAuthority.Admission;
import com.company.requests.domain.Request;
import com.company.requests.domain.RequestState;
import java.util.Objects;
import java.util.Optional;

/** A publication can be used only when the guarded request, journal and projection agree. */
public final class PublicationProof {
    private final PublicationJournal journal;
    private final PublicationConfirmationStore confirmations;

    public PublicationProof(PublicationJournal journal, PublicationConfirmationStore confirmations) {
        this.journal = Objects.requireNonNull(journal);
        this.confirmations = Objects.requireNonNull(confirmations);
    }

    Optional<Entry> confirmed(Admission admission, PolicyGuard guard, Request request,
                              Optional<OperationReceipt> original) {
        if (!guard.organization().equals(admission.genesis().organization())
                || !guard.organization().equals(request.organization())
                || request.state() != RequestState.PUBLISHED || request.publicationId().isEmpty()) {
            return Optional.empty();
        }
        return journal.activeBinding(admission.writer(), guard.organization(), request.type(), request.ref())
                .filter(entry -> entry.state() == State.COMMITTED)
                .filter(entry -> entry.attempt().writer().equals(admission.writer())
                        && entry.attempt().organization().equals(guard.organization())
                        && entry.attempt().type() == request.type() && entry.attempt().ref().equals(request.ref())
                        && entry.attempt().resultRevision() == request.revision()
                        && entry.allocation().equals(request.publicationId()))
                .filter(entry -> original.isEmpty() || matchesReceipt(entry, original.orElseThrow()))
                .filter(entry -> confirmations.contains(guard, entry));
    }

    private static boolean matchesReceipt(Entry entry, OperationReceipt receipt) {
        var attempt = entry.attempt();
        return receipt.keyGeneration() == attempt.writer().generation()
                && receipt.key().organization().equals(attempt.organization())
                && receipt.resultRef().equals(attempt.ref()) && receipt.resultRevision() == attempt.resultRevision()
                && receipt.publicationId().equals(entry.allocation())
                && PublicationJournal.receiptIdentity(receipt.key()).equals(attempt.receiptIdentity());
    }
}
