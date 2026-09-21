package replay.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import replay.Json;

/**
 * Stable fingerprinting. Canonical JSON means:
 *  - object keys sorted lexicographically (UTF-16 order, matching TreeMap),
 *  - compact separators, fixed number formatting,
 *  - no whitespace.
 * Trajectory hashing is a chained SHA-256 so each step authenticates its prefix.
 */
public final class Hashes {

    private Hashes() {
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Object canonicalize(Object value) {
        if (value instanceof Map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                sorted.put(String.valueOf(e.getKey()), canonicalize(e.getValue()));
            }
            return sorted;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) value) {
                out.add(canonicalize(item));
            }
            return out;
        }
        return value;
    }

    public static String canonical(Object value) {
        return Json.write(canonicalize(value));
    }

    public static String fingerprint(Object value) {
        return sha256Hex(canonical(value));
    }

    public static String shortFp(Object value) {
        return fingerprint(value).substring(0, 12);
    }

    /** Chained hash: new hash binds previous hash plus canonical step content. */
    public static String chain(String prevHash, Object stepContent) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("prev", prevHash == null ? "" : prevHash);
        block.put("step", canonicalize(stepContent));
        return fingerprint(block);
    }
}
