package com.replayroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic JSON canonicalization + hashing helpers. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static String canonical(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(node, sb);
        return sb.toString();
    }

    private static void writeCanonical(JsonNode n, StringBuilder sb) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            sb.append("null");
        } else if (n.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                sorted.put(e.getKey(), e.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(e.getKey(), sb);
                sb.append(':');
                writeCanonical(e.getValue(), sb);
            }
            sb.append('}');
        } else if (n.isArray()) {
            sb.append('[');
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) sb.append(',');
                writeCanonical(n.get(i), sb);
            }
            sb.append(']');
        } else if (n.isTextual()) {
            quote(n.textValue(), sb);
        } else if (n.isNumber()) {
            if (n.isIntegralNumber()) {
                sb.append(n.longValue());
            } else {
                sb.append(n.decimalValue().stripTrailingZeros().toPlainString());
            }
        } else if (n.isBoolean()) {
            sb.append(n.booleanValue());
        } else {
            quote(n.asText(), sb);
        }
    }

    static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    public static String sha256(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
