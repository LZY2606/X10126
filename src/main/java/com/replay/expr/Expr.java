package com.replay.expr;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;

import java.util.Map;

/**
 * Tiny deterministic expression language over state variables.
 * Supports: literals (number, "string", true/false/null), identifiers, `state`,
 * == != < <= > >=, && || !, + - (numeric add / string concat), parentheses.
 */
public final class Expr {
    private Expr() {}

    public interface Env {
        JsonElement lookup(String name);
    }

    public static JsonElement eval(String src, Env env) {
        Parser p = new Parser(src, env);
        JsonElement v = p.parseOr();
        p.expectEnd();
        return v;
    }

    public static boolean evalBool(String src, Env env) {
        return truthy(eval(src, env));
    }

    public static boolean truthy(JsonElement e) {
        if (e == null || e.isJsonNull()) return false;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble() != 0;
            return !p.getAsString().isEmpty();
        }
        return true;
    }

    public static String asString(JsonElement e) {
        if (e == null || e.isJsonNull()) return "null";
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return e.getAsString();
        return e.toString();
    }

    private static JsonElement str(String s) { return new JsonPrimitive(s); }
    private static JsonElement num(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.0e15) return new JsonPrimitive((long) d);
        return new JsonPrimitive(d);
    }

    private static final class Parser {
        private final String s;
        private final Env env;
        private int pos;

        Parser(String s, Env env) { this.s = s; this.env = env; }

        private void ws() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }
        private boolean eof() { ws(); return pos >= s.length(); }
        private boolean peek(String t) { ws(); return s.startsWith(t, pos); }
        private boolean eat(String t) { if (peek(t)) { pos += t.length(); return true; } return false; }
        private void expect(String t) { if (!eat(t)) throw error("expected '" + t + "'"); }
        private RuntimeException error(String m) {
            return new IllegalArgumentException("expr error at " + pos + " in \"" + s + "\": " + m);
        }
        void expectEnd() { if (!eof()) throw error("trailing input"); }

        JsonElement parseOr() {
            JsonElement l = parseAnd();
            while (eat("||")) l = new JsonPrimitive(truthy(l) || truthy(parseAnd()));
            return l;
        }

        JsonElement parseAnd() {
            JsonElement l = parseCmp();
            while (eat("&&")) {
                JsonElement r = parseCmp();
                l = new JsonPrimitive(truthy(l) && truthy(r));
            }
            return l;
        }

        JsonElement parseCmp() {
            JsonElement l = parseAdd();
            String[] ops = {"==", "!=", "<=", ">=", "<", ">"};
            for (String op : ops) {
                if (eat(op)) {
                    JsonElement r = parseAdd();
                    return new JsonPrimitive(compare(l, r, op));
                }
            }
            return l;
        }

        JsonElement parseAdd() {
            JsonElement l = parseUnary();
            while (true) {
                if (eat("+")) l = add(l, parseUnary());
                else if (eat("-")) l = sub(l, parseUnary());
                else return l;
            }
        }

        JsonElement parseUnary() {
            if (eat("!")) return new JsonPrimitive(!truthy(parseUnary()));
            if (eat("-")) {
                JsonElement v = parseUnary();
                return num(-asDouble(v));
            }
            return parsePrimary();
        }

        JsonElement parsePrimary() {
            ws();
            if (pos >= s.length()) throw error("unexpected end");
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                JsonElement v = parseOr();
                expect(")");
                return v;
            }
            if (c == '"' || c == '\'') {
                pos++;
                StringBuilder sb = new StringBuilder();
                while (pos < s.length() && s.charAt(pos) != c) {
                    char ch = s.charAt(pos);
                    if (ch == '\\' && pos + 1 < s.length()) {
                        pos++;
                        ch = switch (s.charAt(pos)) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            default -> s.charAt(pos);
                        };
                    }
                    sb.append(ch);
                    pos++;
                }
                if (pos >= s.length()) throw error("unterminated string");
                pos++;
                return str(sb.toString());
            }
            if (Character.isDigit(c) || c == '.') {
                int start = pos;
                while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
                return num(Double.parseDouble(s.substring(start, pos)));
            }
            if (Character.isLetter(c) || c == '_') {
                int start = pos;
                while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) pos++;
                String id = s.substring(start, pos);
                switch (id) {
                    case "true": return new JsonPrimitive(true);
                    case "false": return new JsonPrimitive(false);
                    case "null": return JsonNull.INSTANCE;
                    default:
                        JsonElement v = env.lookup(id);
                        if (v == null) throw error("unknown variable '" + id + "'");
                        return v;
                }
            }
            throw error("unexpected character '" + c + "'");
        }

        private static double asDouble(JsonElement e) {
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsDouble();
            throw new IllegalArgumentException("not a number: " + e);
        }

        private static JsonElement add(JsonElement a, JsonElement b) {
            boolean aNum = a.isJsonPrimitive() && a.getAsJsonPrimitive().isNumber();
            boolean bNum = b.isJsonPrimitive() && b.getAsJsonPrimitive().isNumber();
            if (aNum && bNum) return num(a.getAsDouble() + b.getAsDouble());
            return str(asString(a) + asString(b));
        }

        private static JsonElement sub(JsonElement a, JsonElement b) {
            return num(asDouble(a) - asDouble(b));
        }

        private static boolean compare(JsonElement a, JsonElement b, String op) {
            boolean aNum = a.isJsonPrimitive() && a.getAsJsonPrimitive().isNumber();
            boolean bNum = b.isJsonPrimitive() && b.getAsJsonPrimitive().isNumber();
            if (aNum && bNum) {
                double x = a.getAsDouble(), y = b.getAsDouble();
                return switch (op) {
                    case "==" -> x == y;
                    case "!=" -> x != y;
                    case "<" -> x < y;
                    case "<=" -> x <= y;
                    case ">" -> x > y;
                    case ">=" -> x >= y;
                    default -> throw new IllegalArgumentException("bad op " + op);
                };
            }
            int c = asString(a).compareTo(asString(b));
            boolean eq = a.equals(b);
            return switch (op) {
                case "==" -> eq;
                case "!=" -> !eq;
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                default -> throw new IllegalArgumentException("bad op " + op);
            };
        }
    }
}
