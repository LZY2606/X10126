package dev.replay.crypto;

import dev.replay.json.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 fingerprints over canonical JSON, returned as lowercase hex. */
public final class Fingerprint {
    private Fingerprint() {
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String ofCanonical(Object value) {
        return sha256Hex(Json.writeCanonical(value));
    }

    public static String shortHash(String full, int length) {
        return full.length() <= length ? full : full.substring(0, length);
    }
}
