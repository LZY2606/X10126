package replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** JSON helpers: canonical serialization and stable fingerprints. */
public final class Json {
    public static final ObjectMapper M = new ObjectMapper();
    private static final ObjectMapper CANON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {}

    public static String canonical(Object value) {
        try {
            return CANON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize: " + e.getMessage(), e);
        }
    }

    public static String pretty(Object value) {
        try {
            return M.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize: " + e.getMessage(), e);
        }
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stable fingerprint of any JSON-serializable value. */
    public static String fingerprint(Object value) {
        return sha256(canonical(value));
    }
}
