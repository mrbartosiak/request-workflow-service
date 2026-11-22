package com.company.requests.application;

import com.company.requests.domain.Action;
import com.company.requests.domain.RequestRef;
import com.company.requests.domain.RequestType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;

/** Derives the receipt digest from the exact decoded values of one typed command. */
public final class IntentDigest {
    private static final int MAX_NAME_BYTES = 256;
    private static final int MAX_BODY_BYTES = 16_384;
    private static final int MAX_REASON_BYTES = 2_048;
    private static final int MAX_OBSERVATION_BYTES = 2_048;
    private static final int MAX_REF_BYTES = 128;
    private static final int HMAC_HEX_LENGTH = 64;

    private final OperationSecrets secrets;

    public IntentDigest(OperationSecrets secrets) {
        this.secrets = Objects.requireNonNull(secrets, "operation secrets");
    }

    public ReceiptIntentDigest calculate(Intent intent) {
        return calculate(secrets.digestVersion(), intent);
    }

    /** Compare with a retained derivation version. Unusable comparison material closes the boundary. */
    public boolean matches(ReceiptIntentDigest stored, Intent intent) {
        if (stored.hex().length() != HMAC_HEX_LENGTH) {
            throw new AuthorityUnavailableException();
        }
        var submitted = calculate(stored.version(), intent);
        return MessageDigest.isEqual(stored.hex().getBytes(StandardCharsets.US_ASCII),
                submitted.hex().getBytes(StandardCharsets.US_ASCII));
    }

    private ReceiptIntentDigest calculate(int version, Intent intent) {
        var mac = secrets.digestMac(version);
        add(mac, Field.VERSION, integerBytes(version));
        switch (intent) {
            case Create create -> {
                addSymbol(mac, Field.ACTION, Action.CREATE);
                addSymbol(mac, Field.CREATE_TYPE, create.type());
                addText(mac, Field.NAME, create.name(), MAX_NAME_BYTES);
                addText(mac, Field.BODY, create.body(), MAX_BODY_BYTES);
            }
            case Edit edit -> {
                addTarget(mac, Action.EDIT, edit.ref());
                addText(mac, Field.BODY, edit.body(), MAX_BODY_BYTES);
                addText(mac, Field.OBSERVATION, edit.observation(), MAX_OBSERVATION_BYTES);
            }
            case Transition transition -> {
                addTarget(mac, transition.action(), transition.ref());
                transition.reason().ifPresent(reason -> addText(mac, Field.REASON, reason, MAX_REASON_BYTES));
                addText(mac, Field.OBSERVATION, transition.observation(), MAX_OBSERVATION_BYTES);
            }
        }
        return new ReceiptIntentDigest(version, HexFormat.of().formatHex(mac.doFinal()));
    }

    private static void addTarget(Mac mac, Action action, RequestRef ref) {
        addSymbol(mac, Field.ACTION, action);
        addText(mac, Field.REF, ref.value(), MAX_REF_BYTES);
    }

    private static void addSymbol(Mac mac, Field field, Enum<?> value) {
        add(mac, field, value.name().getBytes(StandardCharsets.US_ASCII));
    }

    private static void addText(Mac mac, Field field, String value, int maxBytes) {
        add(mac, field, ExactOperationText.encode(value, maxBytes));
    }

    private static void add(Mac mac, Field field, byte[] bytes) {
        mac.update(field.tag);
        mac.update(integerBytes(bytes.length));
        mac.update(bytes);
    }

    private static byte[] integerBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private enum Field {
        VERSION(1), ACTION(2), CREATE_TYPE(3), REF(4), NAME(5), BODY(6), REASON(7), OBSERVATION(8);

        private final byte tag;

        Field(int tag) {
            this.tag = (byte) tag;
        }
    }

    public sealed interface Intent permits Create, Edit, Transition {
    }

    public record Create(RequestType type, String name, String body) implements Intent {
        public Create {
            Objects.requireNonNull(type, "create type");
        }

        @Override
        public String toString() {
            return "CreateIntent[protected]";
        }
    }

    public record Edit(RequestRef ref, String body, String observation) implements Intent {
        public Edit {
            Objects.requireNonNull(ref, "request reference");
        }

        @Override
        public String toString() {
            return "EditIntent[protected]";
        }
    }

    public record Transition(Action action, RequestRef ref, Optional<String> reason, String observation)
            implements Intent {
        public Transition {
            Objects.requireNonNull(action, "transition action");
            Objects.requireNonNull(ref, "request reference");
            Objects.requireNonNull(reason, "transition reason");
            boolean requiresReason = action == Action.DELETE || action == Action.REJECT;
            if (action == Action.CREATE || action == Action.EDIT || reason.isPresent() != requiresReason) {
                throw new IllegalArgumentException("Invalid transition intent.");
            }
        }

        @Override
        public String toString() {
            return "TransitionIntent[protected]";
        }
    }
}
