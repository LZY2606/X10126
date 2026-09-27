package replay.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 fingerprint helpers over canonical JSON. */
public final class Hashes {
    private Hashes() {}

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String fingerprint(Object value) {
        return sha256Hex(Json.canonical(value));
    }

    public static String shortHex(String hex) {
        return hex.length() <= 12 ? hex : hex.substring(0, 12);
    }
}
