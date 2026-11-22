package com.company.requests.adapter.authority;

import java.util.function.Supplier;
import javax.sql.DataSource;

/** The real-engine fixture supplies deterministic collisions without exposing an entropy override in production. */
public final class B06ReservationFixture {
    private B06ReservationFixture() { }

    public static JdbcSyntheticAuthority withEntropy(DataSource proof, Supplier<byte[]> entropy) {
        return new JdbcSyntheticAuthority(proof, entropy);
    }
}
