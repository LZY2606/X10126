package replay.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 指纹工具：规范化 JSON（对象键排序）后做 SHA-256，返回 16 进制串。 */
public final class Hash {

    private Hash() {
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 把对象转换为键有序的规范化结构（TreeMap / ArrayList），便于稳定指纹。 */
    public static Object canonical(Object value) {
        if (value instanceof Map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                sorted.put(String.valueOf(e.getKey()), canonical(e.getValue()));
            }
            return sorted;
        }
        if (value instanceof Iterable) {
            List<Object> out = new ArrayList<>();
            for (Object item : (Iterable<?>) value) {
                out.add(canonical(item));
            }
            return out;
        }
        return value;
    }

    /** 规范化 JSON 文本：紧凑、键按字典序。 */
    public static String canonicalJson(Object value) {
        return Json.write(canonical(value));
    }

    public static String fingerprint(Object value) {
        return sha256Hex(canonicalJson(value));
    }
}
