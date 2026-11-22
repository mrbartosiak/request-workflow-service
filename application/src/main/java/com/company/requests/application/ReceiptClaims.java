package com.company.requests.application;

import java.time.Instant;

/** One scoped key across command routes, using the owned transaction for tentative claims. */
public interface ReceiptClaims {
    boolean claim(ReceiptKey key, ReceiptIntentDigest digest, long generation, Instant expiry);

    void finalizeReceipt(OperationReceipt receipt);
}
