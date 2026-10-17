package com.dbonilla.ticketing.api.model.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Deterministic order identity (ADR-0004): {@code orderId = UUIDv5(namespace, userId + ":" + key)} and
 * {@code requestHash = SHA-256(canonical payload)}.
 */
public final class OrderIdentity {

    public static final UUID NAMESPACE_TICKETING = UUID.fromString("6f3c2a1e-9b4d-4c7e-8a15-2d9e0b7c4f61");

    private static final int VERSION_5 = 0x50;
    private static final int VERSION_MASK = 0x0f;
    private static final int VARIANT_IETF = 0x80;
    private static final int VARIANT_MASK = 0x3f;
    private static final int UUID_BYTES = 16;
    private static final int LONG_BYTES = 8;

    private OrderIdentity() {
    }

    public static String orderId(String userId, String idempotencyKey) {
        return uuidV5(NAMESPACE_TICKETING, userId + ":" + idempotencyKey).toString();
    }

    public static String requestHash(String canonicalPayload) {
        byte[] hash = digest("SHA-256").digest(canonicalPayload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    static UUID uuidV5(UUID namespace, String name) {
        MessageDigest sha1 = digest("SHA-1");
        sha1.update(toBytes(namespace));
        sha1.update(name.getBytes(StandardCharsets.UTF_8));
        byte[] hash = sha1.digest();
        hash[6] = (byte) ((hash[6] & VERSION_MASK) | VERSION_5);
        hash[8] = (byte) ((hash[8] & VARIANT_MASK) | VARIANT_IETF);
        return new UUID(toLong(hash, 0), toLong(hash, LONG_BYTES));
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] bytes = new byte[UUID_BYTES];
        for (int i = 0; i < LONG_BYTES; i++) {
            bytes[i] = (byte) (uuid.getMostSignificantBits() >>> (LONG_BYTES * (LONG_BYTES - 1 - i)));
            bytes[LONG_BYTES + i] = (byte) (uuid.getLeastSignificantBits() >>> (LONG_BYTES * (LONG_BYTES - 1 - i)));
        }
        return bytes;
    }

    private static long toLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = offset; i < offset + LONG_BYTES; i++) {
            value = (value << LONG_BYTES) | (bytes[i] & 0xff);
        }
        return value;
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }
}
