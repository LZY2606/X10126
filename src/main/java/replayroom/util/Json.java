package replayroom.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Json {
    public static final ObjectMapper M = new ObjectMapper();

    private Json() {
    }

    /** 递归按键名排序，生成规范化 JSON 节点，保证相同内容得到相同字节序列。 */
    public static JsonNode canonical(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode arr = M.createArrayNode();
            for (JsonNode child : node) {
                arr.add(canonical(child));
            }
            return arr;
        }
        ObjectNode obj = M.createObjectNode();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);
        for (String name : names) {
            obj.set(name, canonical(node.get(name)));
        }
        return obj;
    }

    public static String canonicalString(JsonNode node) {
        try {
            return M.writeValueAsString(canonical(node));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
