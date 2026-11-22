package com.company.requests.application;

import com.company.requests.application.PublicationJournal.Attempt;
import com.company.requests.application.PublicationJournal.Entry;
import com.company.requests.domain.RequestRef;

/** Transaction-owned namespace gate and append boundary for the original publication witness. */
public interface PublishGate {
    /** Lock the scoped ACCEPTED request and then the shared namespace until the owned transaction ends. */
    Attempt acquire(PolicyGuard guard, WriterEpoch writer, RequestRef ref, ReceiptKey receipt);

    /** Append only while the original command still holds its gate, after storing its successful receipt. */
    void appendWitness(Entry pending, OperationReceipt receipt);
}
