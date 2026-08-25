package com.lobster.util;

import java.security.SecureRandom;
import java.time.Instant;

/** Crockford Base32 ULID。线程安全，单调递增（同毫秒内熵递增）。 */
public final class Ulid {
    private static final char[] ENC = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RND = new SecureRandom();
    private static long lastTs = -1;
    private static final byte[] lastEntropy = new byte[10];

    private Ulid() {}

    public static synchronized String next(String prefix) {
        long ts = Instant.now().toEpochMilli();
        byte[] entropy = new byte[10];
        if (ts == lastTs) {
            for (int i = 9; i >= 0; i--) {
                lastEntropy[i]++;
                if (lastEntropy[i] != 0) break;
            }
            System.arraycopy(lastEntropy, 0, entropy, 0, 10);
        } else {
            RND.nextBytes(entropy);
        }
        lastTs = ts;
        System.arraycopy(entropy, 0, lastEntropy, 0, 10);

        char[] out = new char[26];
        long t = ts;
        for (int i = 9; i >= 0; i--) { out[i] = ENC[(int) (t & 0x1F)]; t >>>= 5; }

        long hi = 0;
        for (int i = 0; i < 5; i++) hi = (hi << 8) | (entropy[i] & 255L);
        long lo = 0;
        for (int i = 5; i < 10; i++) lo = (lo << 8) | (entropy[i] & 255L);
        for (int i = 25; i >= 18; i--) { out[i] = ENC[(int) (hi & 0x1F)]; hi >>>= 5; }
        for (int i = 17; i >= 10; i--) { out[i] = ENC[(int) (lo & 0x1F)]; lo >>>= 5; }
        return prefix + new String(out);
    }
}
