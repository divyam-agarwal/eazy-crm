package com.easycrm.platform.persistence;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates RFC 9562 version-7 UUIDs: a 48-bit big-endian Unix-millisecond timestamp
 * followed by 74 random bits (with the version/variant nibbles fixed). Time-ordered like
 * the Hibernate {@code @UuidGenerator(style = TIME)} used elsewhere, but generated in
 * application code so an id is available BEFORE the row is inserted — required when the
 * tenant context must be set before the transaction that inserts the tenant's first rows.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID generate() {
        long ts = System.currentTimeMillis();
        byte[] b = new byte[16];
        b[0] = (byte) (ts >>> 40);
        b[1] = (byte) (ts >>> 32);
        b[2] = (byte) (ts >>> 24);
        b[3] = (byte) (ts >>> 16);
        b[4] = (byte) (ts >>> 8);
        b[5] = (byte) ts;

        byte[] rand = new byte[10];
        RANDOM.nextBytes(rand);
        System.arraycopy(rand, 0, b, 6, 10);

        b[6] = (byte) ((b[6] & 0x0F) | 0x70); // version 7
        b[8] = (byte) ((b[8] & 0x3F) | 0x80); // variant 10

        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) msb = (msb << 8) | (b[i] & 0xFF);
        for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (b[i] & 0xFF);
        return new UUID(msb, lsb);
    }
}
