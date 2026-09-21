package replayroom.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import replayroom.json.Json;

/** Recursive descent parser for the {@link Expr} language. */
final class ExprParser {

    private final String s;
    private int pos;

    ExprParser(String s) { this.s = s; }

    Expr.Node parse() {
        Expr.Node node = parseOr();
        skipSpaces();
        if (pos < s.length()) throw new Expr.EvalException("Unexpected character '" + s.charAt(pos) + "'");
        return node;
    }

    private void skipSpaces() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    private boolean consume(char c) {
        skipSpaces();
        if (pos < s.length() && s.charAt(pos) == c) { pos++; return true; }
        return false;
    }

    private boolean match(String op) {
        skipSpaces();
        if (s.startsWith(op, pos)) { pos += op.length(); return true; }
        return false;
    }

    private Expr.Node parseOr() {
        Expr.Node left = parseAnd();
        while (match("||")) {
            Expr.Node right = parseAnd();
            Expr.Node captured = left;
            left = ctx -> Expr.truthy(captured.eval(ctx)) || Expr.truthy(right.eval(ctx));
        }
        return left;
    }

    private Expr.Node parseAnd() {
        Expr.Node left = parseComparison();
        while (match("&&")) {
            Expr.Node right = parseComparison();
            Expr.Node captured = left;
            left = ctx -> Expr.truthy(captured.eval(ctx)) && Expr.truthy(right.eval(ctx));
        }
        return left;
    }

    private Expr.Node parseComparison() {
        Expr.Node left = parseAddition();
        String op;
        skipSpaces();
        if ((op = pickOp("==", "!=", "<=", ">=", "<", ">")) != null) {
            Expr.Node right = parseAddition();
            return ctx -> compare(op, left.eval(ctx), right.eval(ctx));
        }
        return left;
    }

    private String pickOp(String... ops) {
        for (String op : ops) {
            if (s.startsWith(op, pos)) { pos += op.length(); return op; }
        }
        return null;
    }

    private Expr.Node parseAddition() {
        Expr.Node left = parseMultiplication();
        while (true) {
            skipSpaces();
            String op;
            if (s.startsWith("+", pos)) op = "+";
            else if (s.startsWith("-", pos)) op = "-";
            else return left;
            pos++;
            Expr.Node right = parseMultiplication();
            Expr.Node captured = left;
            left = ctx -> arithmetic(op, captured.eval(ctx), right.eval(ctx));
        }
    }

    private Expr.Node parseMultiplication() {
        Expr.Node left = parseUnary();
        while (true) {
            skipSpaces();
            String op;
            if (s.startsWith("*", pos)) op = "*";
            else if (s.startsWith("/", pos)) op = "/";
            else if (s.startsWith("%", pos)) op = "%";
            else return left;
            pos++;
            Expr.Node right = parseUnary();
            Expr.Node captured = left;
            left = ctx -> arithmetic(op, captured.eval(ctx), right.eval(ctx));
        }
    }

    private Expr.Node parseUnary() {
        if (consume('!')) {
            Expr.Node inner = parseUnary();
            return ctx -> !Expr.truthy(inner.eval(ctx));
        }
        if (consume('-')) {
            Expr.Node inner = parseUnary();
            return ctx -> -Expr.asDouble(inner.eval(ctx), "unary -");
        }
        return parsePostfix();
    }

    private Expr.Node parsePostfix() {
        Expr.Node node = parsePrimary();
        while (true) {
            skipSpaces();
            if (pos >= s.length()) return node;
            char c = s.charAt(pos);
            if (c == '.') {
                pos++;
                String field = readName();
                Expr.Node base = node;
                node = ctx -> fieldAccess(base.eval(ctx), field);
            } else if (c == '[') {
                pos++;
                Expr.Node index = parseOr();
                if (!consume(']')) throw new Expr.EvalException("Expected ']'");
                Expr.Node base = node;
                node = ctx -> indexAccess(base.eval(ctx), index.eval(ctx));
            } else if (c == '(') {
                if (!(node instanceof NameNode)) throw new Expr.EvalException("Only named functions can be called");
                String fn = ((NameNode) node).name;
                pos++;
                List<Expr.Node> args = new ArrayList<>();
                skipSpaces();
                if (!s.startsWith(")", pos)) {
                    args.add(parseOr());
                    skipSpaces();
                    while (consume(',')) {
                        args.add(parseOr());
                    }
                }
                if (!consume(')')) throw new Expr.EvalException("Expected ')'");
                node = ctx -> callFunction(fn, args, ctx);
            } else {
                return node;
            }
        }
    }

    private Expr.Node parsePrimary() {
        skipSpaces();
        if (pos >= s.length()) throw new Expr.EvalException("Unexpected end of expression");
        char c = s.charAt(pos);
        if (c == '(') {
            pos++;
            Expr.Node inner = parseOr();
            if (!consume(')')) throw new Expr.EvalException("Expected ')'");
            return inner;
        }
        if (c == '{') return parseObjectLiteral();
        if (c == '[') return parseArrayLiteral();
        if (c == '"' || c == '\'') return parseStringLiteral(c);
        if (Character.isDigit(c) || (c == '.' && pos + 1 < s.length() && Character.isDigit(s.charAt(pos + 1)))) {
            return parseNumberLiteral();
        }
        if (isNameStart(c)) {
            String name = readName();
            return switch (name) {
                case "true" -> ctx -> Boolean.TRUE;
                case "false" -> ctx -> Boolean.FALSE;
                case "null" -> ctx -> null;
                default -> new NameNode(name);
            };
        }
        throw new Expr.EvalException("Unexpected character '" + c + "'");
    }


    private Expr.Node parseObjectLiteral() {
        pos++;
        skipSpaces();
        List<String> keys = new ArrayList<>();
        List<Expr.Node> values = new ArrayList<>();
        if (!s.startsWith("}", pos)) {
            while (true) {
                skipSpaces();
                String key;
                if (pos < s.length() && (s.charAt(pos) == '"' || s.charAt(pos) == '\'')) {
                    char quote = s.charAt(pos);
                    Expr.Node literal = parseStringLiteral(quote);
                    key = String.valueOf(literal.eval(new Expr.Eval(new java.util.LinkedHashMap<>(), null)));
                } else {
                    key = readName();
                }
                if (!consume(':')) throw new Expr.EvalException("Expected ':' in object literal");
                keys.add(key);
                values.add(parseOr());
                skipSpaces();
                if (consume(',')) continue;
                if (!consume('}')) throw new Expr.EvalException("Expected '}' in object literal");
                break;
            }
        } else {
            pos++;
        }
        return ctx -> {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) map.put(keys.get(i), values.get(i).eval(ctx));
            return map;
        };
    }

    private Expr.Node parseArrayLiteral() {
        pos++;
        List<Expr.Node> items = new ArrayList<>();
        skipSpaces();
        if (!s.startsWith("]", pos)) {
            while (true) {
                items.add(parseOr());
                skipSpaces();
                if (consume(',')) continue;
                if (!consume(']')) throw new Expr.EvalException("Expected ']' in array literal");
                break;
            }
        } else {
            pos++;
        }
        return ctx -> {
            List<Object> list = new ArrayList<>();
            for (Expr.Node item : items) list.add(item.eval(ctx));
            return list;
        };
    }

    private Expr.Node parseStringLiteral(char quote) {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < s.length() && s.charAt(pos) != quote) {
            char ch = s.charAt(pos++);
            if (ch == '\\' && pos < s.length()) {
                char esc = s.charAt(pos++);
                switch (esc) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    default -> sb.append(esc);
                }
            } else {
                sb.append(ch);
            }
        }
        if (pos >= s.length()) throw new Expr.EvalException("Unterminated string");
        pos++;
        String value = sb.toString();
        return ctx -> value;
    }

    private Expr.Node parseNumberLiteral() {
        int start = pos;
        while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
        boolean isDouble = false;
        if (pos < s.length() && s.charAt(pos) == '.') {
            isDouble = true;
            pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            isDouble = true;
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
        }
        String token = s.substring(start, pos);
        if (isDouble) {
            double d = Double.parseDouble(token);
            return ctx -> d;
        }
        long l = Long.parseLong(token);
        return ctx -> l;
    }

    private String readName() {
        skipSpaces();
        int start = pos;
        while (pos < s.length() && isNamePart(s.charAt(pos))) pos++;
        if (start == pos) throw new Expr.EvalException("Expected name");
        return s.substring(start, pos);
    }

    private boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static final class NameNode implements Expr.Node {
        final String name;
        NameNode(String name) { this.name = name; }
        @Override public Object eval(Expr.Eval ctx) {
            if (!ctx.root.containsKey(name)) throw new Expr.EvalException("Unknown variable '" + name + "'");
            return ctx.root.get(name);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object fieldAccess(Object value, String field) {
        if (value instanceof Map<?, ?> map) return ((Map<String, Object>) map).get(field);
        if (value == null) return null;
        throw new Expr.EvalException("Cannot read field '" + field + "' on " + typeName(value));
    }

    @SuppressWarnings("unchecked")
    private static Object indexAccess(Object value, Object key) {
        if (value instanceof Map<?, ?> map) {
            return ((Map<String, Object>) map).get(String.valueOf(key));
        }
        if (value instanceof List<?> list) {
            int i = (int) (key instanceof Number n ? n.longValue() : Math.round(Expr.asDouble(key, "index")));
            if (i < 0 || i >= list.size()) throw new Expr.EvalException("Index out of range: " + i);
            return list.get(i);
        }
        throw new Expr.EvalException("Cannot index " + typeName(value));
    }

    private static String typeName(Object value) {
        if (value == null) return "null";
        if (value instanceof Map) return "object";
        if (value instanceof List) return "array";
        return value.getClass().getSimpleName().toLowerCase();
    }

    private static Object callFunction(String fn, List<Expr.Node> args, Expr.Eval ctx) {
        switch (fn) {
            case "rand" -> {
                checkArity(fn, args.size(), 0, 0);
                return ctx.rng.nextUnit();
            }
            case "randInt" -> {
                checkArity(fn, args.size(), 2, 2);
                long low = Math.round(Expr.asDouble(args.get(0).eval(ctx), "randInt low"));
                long high = Math.round(Expr.asDouble(args.get(1).eval(ctx), "randInt high"));
                if (high <= low) throw new Expr.EvalException("randInt: low must be less than high");
                long range = high - low;
                return low + (long) Math.floor(ctx.rng.nextUnit() * range);
            }
            case "len" -> {
                checkArity(fn, args.size(), 1, 1);
                Object v = args.get(0).eval(ctx);
                if (v instanceof List<?> l) return (long) l.size();
                if (v instanceof Map<?, ?> m) return (long) m.size();
                if (v instanceof String str) return (long) str.length();
                throw new Expr.EvalException("len() expects array, object or string");
            }
            case "str" -> {
                checkArity(fn, args.size(), 1, 1);
                Object v = args.get(0).eval(ctx);
                if (v == null) return "null";
                if (v instanceof Map || v instanceof List) return Json.canonical(v);
                return String.valueOf(v);
            }
            case "num" -> {
                checkArity(fn, args.size(), 1, 1);
                return Expr.asDouble(args.get(0).eval(ctx), "num()");
            }
            case "bool" -> {
                checkArity(fn, args.size(), 1, 1);
                return Expr.truthy(args.get(0).eval(ctx));
            }
            default -> throw new Expr.EvalException("Unknown function '" + fn + "'");
        }
    }

    private static void checkArity(String fn, int actual, int min, int max) {
        if (actual < min || actual > max) {
            throw new Expr.EvalException(fn + "() expects " + min + ".." + max + " arguments, got " + actual);
        }
    }

    private static Object compare(String op, Object a, Object b) {
        if ("==".equals(op)) return java.util.Objects.equals(normalizeEquals(a), normalizeEquals(b));
        if ("!=".equals(op)) return !java.util.Objects.equals(normalizeEquals(a), normalizeEquals(b));
        if (a instanceof String || b instanceof String) {
            int cmp = String.valueOf(a).compareTo(String.valueOf(b));
            return compareResult(op, cmp);
        }
        int cmp = Double.compare(Expr.asDouble(a, "comparison"), Expr.asDouble(b, "comparison"));
        return compareResult(op, cmp);
    }

    private static Object normalizeEquals(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15) {
            return d.longValue();
        }
        return v;
    }

    private static boolean compareResult(String op, int cmp) {
        return switch (op) {
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            default -> false;
        };
    }

    private static Object arithmetic(String op, Object a, Object b) {
        if ("+".equals(op) && (a instanceof String || b instanceof String)) {
            return String.valueOf(a == null ? "null" : a) + (b == null ? "null" : b);
        }
        double x = Expr.asDouble(a, op);
        double y = Expr.asDouble(b, op);
        double result = switch (op) {
            case "+" -> x + y;
            case "-" -> x - y;
            case "*" -> x * y;
            case "/" -> {
                if (y == 0.0) throw new Expr.EvalException("Division by zero");
                yield x / y;
            }
            case "%" -> {
                if (y == 0.0) throw new Expr.EvalException("Modulo by zero");
                yield x % y;
            }
            default -> throw new Expr.EvalException("Unknown operator " + op);
        };
        if (result == Math.rint(result) && Math.abs(result) < 9.007199254740992E15) {
            return (long) result;
        }
        return result;
    }
}
