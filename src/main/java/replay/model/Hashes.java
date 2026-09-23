package replay.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 fingerprint helpers over canonical JSON and arbitrary text. */
public final class Hashes {

    private Hashes() {}

    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
    }

    public static String fingerprint(Object jsonValue) {
        return sha256Hex(Json.canonical(jsonValue));
    }

    /** Short, human-readable prefix for log/UI display. */
    public static String shortHash(String hex) {
        return hex == null ? "-" : hex.substring(0, Math.min(12, hex.length()));
    }
}
