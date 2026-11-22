package com.company.requests.application;

import java.util.HexFormat;
import java.util.Objects;

/** Versioned keyed digest of the exact original intent. */
public record ReceiptIntentDigest(int version, String hex) {
    public ReceiptIntentDigest {
        Objects.requireNonNull(hex, "digest");
        if (version < 1 || hex.isEmpty()) {
            throw new IllegalArgumentException("A positive version and digest bytes are required.");
        }
        try {
            hex = HexFormat.of().formatHex(HexFormat.of().parseHex(hex));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("The intent digest must be hexadecimal.");
        }
    }

    @Override
    public String toString() {
        return "ReceiptIntentDigest[protected]";
    }
}
