package com.company.requests.application;

import com.company.requests.domain.OrganizationId;
import com.company.requests.domain.PublicationId;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable publication decisions outside the workflow restore boundary. Results are internal proof. */
public interface PublicationJournal {
    Entry register(Attempt attempt);

    Entry reserve(Attempt attempt);

    Optional<Entry> find(Attempt attempt);

    Optional<Entry> activeBinding(WriterEpoch writer, OrganizationId organization, RequestType type, RequestRef ref);

    Entry confirm(Attempt attempt);

    Entry abort(Attempt attempt);

    /** Bind a receipt's exact scoped key without copying the key into the independent journal. */
    static String receiptIdentity(ReceiptKey key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String component : new String[]{"requests.publication.receipt-key.v1", key.issuer(),
                    key.organization().value(), key.principal(), key.tokenId()}) {
                byte[] value = ExactOperationText.encode(component, Integer.MAX_VALUE);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    record Attempt(UUID id, WriterEpoch writer, OrganizationId organization, RequestType type, RequestRef ref,
                   int backendPid, String workflowTransaction, String receiptIdentity, long resultRevision) {
        public Attempt {
            Objects.requireNonNull(id);
            Objects.requireNonNull(writer);
            Objects.requireNonNull(organization);
            Objects.requireNonNull(type);
            Objects.requireNonNull(ref);
            Objects.requireNonNull(workflowTransaction);
            Objects.requireNonNull(receiptIdentity);
            if (backendPid < 1 || !workflowTransaction.matches("[1-9][0-9]{0,19}")
                    || Long.parseUnsignedLong(workflowTransaction) == 0
                    || !receiptIdentity.matches("[0-9a-f]{64}") || resultRevision < 2) {
                throw new IllegalArgumentException("Invalid publication attempt coordinates.");
            }
        }

        public String namespace() {
            return PublicationId.INITIAL_NAMESPACE;
        }

        @Override
        public String toString() {
            return "PublicationAttempt[protected]";
        }
    }

    enum State {
        REGISTERED, PENDING, COMMITTED, ABORTED
    }

    record Entry(Attempt attempt, Optional<PublicationId> allocation, State state) {
        public Entry {
            Objects.requireNonNull(attempt);
            Objects.requireNonNull(allocation);
            Objects.requireNonNull(state);
            if ((state == State.REGISTERED && allocation.isPresent())
                    || ((state == State.PENDING || state == State.COMMITTED) && allocation.isEmpty())) {
                throw new IllegalArgumentException("Invalid publication decision.");
            }
        }

        @Override
        public String toString() {
            return "PublicationEntry[state=" + state + "]";
        }
    }
}
