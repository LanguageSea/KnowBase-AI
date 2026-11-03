package com.knowbase.doc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** 与 Python qdrant_store.point_id 完全一致的 RFC 4122 v5 UUID。 */
public final class PointIds {

    private static final byte[] NAMESPACE_URL = new byte[]{
            (byte) 0x6b, (byte) 0xa7, (byte) 0xb8, (byte) 0x11,
            (byte) 0x9d, (byte) 0xad, (byte) 0x11, (byte) 0xd1,
            (byte) 0x80, (byte) 0xb4, (byte) 0x00, (byte) 0xc0,
            (byte) 0x4f, (byte) 0xd4, (byte) 0x30, (byte) 0xc8};

    private PointIds() {
    }

    public static String forChunk(long docId, int seq) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(NAMESPACE_URL);
            sha1.update(("knowbase:chunk:" + docId + ":" + seq).getBytes(StandardCharsets.UTF_8));
            byte[] hash = sha1.digest();
            hash[6] &= 0x0f;
            hash[6] |= 0x50;
            hash[8] &= 0x3f;
            hash[8] |= (byte) 0x80;
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (hash[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (hash[i] & 0xff);
            }
            return new UUID(msb, lsb).toString();
        } catch (Exception ex) {
            throw new IllegalStateException("生成 Qdrant point id 失败", ex);
        }
    }
}
