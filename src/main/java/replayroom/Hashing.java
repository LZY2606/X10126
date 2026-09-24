package replayroom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** SHA-256 指纹工具：所有逻辑对象先经 Json.canonical 规范化。 */
public final class Hashing {
    private Hashing() {}

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String fingerprint(Object canonicalizable) {
        return sha256Hex(Json.canonical(canonicalizable));
    }
}
