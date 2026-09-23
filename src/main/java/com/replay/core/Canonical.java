package com.replay.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/** Canonical JSON serialization (sorted keys, normalized numbers) + SHA-256 hashing. */
public final class Canonical {
    private Canonical() {}

    public static JsonElement normalize(JsonElement e) {
        if (e == null || e.isJsonNull()) return JsonNull.INSTANCE;
        if (e.isJsonObject()) {
            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                sorted.put(en.getKey(), normalize(en.getValue()));
            }
            JsonObject o = new JsonObject();
            for (Map.Entry<String, JsonElement> en : sorted.entrySet()) o.add(en.getKey(), en.getValue());
            return o;
        }
        if (e.isJsonArray()) {
            JsonArray a = new JsonArray();
            for (JsonElement item : e.getAsJsonArray()) a.add(normalize(item));
            return a;
        }
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) {
                double d = p.getAsDouble();
                if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.0e15) {
                    return new JsonPrimitive((long) d);
                }
                return new JsonPrimitive(d);
            }
            return p;
        }
        return e;
    }

    public static String canonical(JsonElement e) {
        return normalize(e).toString();
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String fingerprint(JsonElement e) {
        return sha256(canonical(e));
    }
}
