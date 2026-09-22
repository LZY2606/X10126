package com.replayroom.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tiny deterministic expression language used by rule conditions and actions.
 * Supports numbers, strings, booleans, variables, {@code state}, {@code time},
 * {@code payload.field}, arithmetic/comparison/logic operators and {@code rand([n])}.
 */
public abstract class Expr {

    public abstract Object eval(EvalContext ctx);

    public static Expr parse(String source) {
        return new Parser(source).parse();
    }

    /** Parse + evaluate convenience. */
    public static Object evaluate(String source, EvalContext ctx) {
        return parse(source).eval(ctx);
    }

    static double num(Object v, String what) {
        if (v instanceof Number n) return n.doubleValue();
        throw new EvalException("expected number for " + what + " but got " + show(v));
    }

    static boolean bool(Object v, String what) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        throw new EvalException("expected boolean for " + what + " but got " + show(v));
    }

    static String show(Object v) {
        return v == null ? "null" : v + " (" + v.getClass().getSimpleName() + ")";
    }

    public static String display(Object v) {
        if (v == null) return "null";
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
            return Double.toString(d);
        }
        return String.valueOf(v);
    }

    // ---------- nodes ----------

    static final class Lit extends Expr {
        final Object value;
        Lit(Object value) { this.value = value; }
        @Override public Object eval(EvalContext ctx) { return value; }
    }

    static final class Var extends Expr {
        final String name;
        Var(String name) { this.name = name; }
        @Override public Object eval(EvalContext ctx) {
            switch (name) {
                case "state": return ctx.state;
                case "time": return ctx.time;
                case "payload": return ctx.payload;
                default:
                    if (ctx.vars.containsKey(name)) return ctx.vars.get(name);
                    throw new EvalException("unknown variable '" + name + "'");
            }
        }
    }

    static final class Field extends Expr {
        final Expr target;
        final String name;
        Field(Expr target, String name) { this.target = target; this.name = name; }
        @Override public Object eval(EvalContext ctx) {
            Object t = target.eval(ctx);
            if (t instanceof Map<?, ?> m && m.containsKey(name)) return m.get(name);
            throw new EvalException("no field '" + name + "' on " + show(t));
        }
    }

    static final class Call extends Expr {
        final String name;
        final List<Expr> args;
        Call(String name, List<Expr> args) { this.name = name; this.args = args; }
        @Override public Object eval(EvalContext ctx) {
            if (name.equals("rand")) {
                if (args.isEmpty()) return ctx.rng.nextDouble();
                if (args.size() == 1) {
                    double bound = num(args.get(0).eval(ctx), "rand(bound)");
                    if (bound <= 0) throw new EvalException("rand bound must be positive");
                    return (double) ctx.rng.nextLong((long) bound);
                }
                throw new EvalException("rand takes 0 or 1 arguments");
            }
            throw new EvalException("unknown function '" + name + "'");
        }
    }

    static final class Un extends Expr {
        final String op;
        final Expr a;
        Un(String op, Expr a) { this.op = op; this.a = a; }
        @Override public Object eval(EvalContext ctx) {
            Object v = a.eval(ctx);
            return switch (op) {
                case "-" -> -num(v, "unary -");
                case "!" -> !bool(v, "!");
                default -> throw new EvalException("bad unary op " + op);
            };
        }
    }

    static final class Bin extends Expr {
        final String op;
        final Expr l, r;
        Bin(String op, Expr l, Expr r) { this.op = op; this.l = l; this.r = r; }
        @Override public Object eval(EvalContext ctx) {
            switch (op) {
                case "&&": {
                    Object lv = l.eval(ctx);
                    if (!bool(lv, "&&")) return false;
                    return bool(r.eval(ctx), "&&");
                }
                case "||": {
                    Object lv = l.eval(ctx);
                    if (bool(lv, "||")) return true;
                    return bool(r.eval(ctx), "||");
                }
            }
            Object lv = l.eval(ctx);
            Object rv = r.eval(ctx);
            switch (op) {
                case "+":
                    if (lv instanceof String || rv instanceof String) return display(lv) + display(rv);
                    return num(lv, "+") + num(rv, "+");
                case "-": return num(lv, "-") - num(rv, "-");
                case "*": return num(lv, "*") * num(rv, "*");
                case "/": {
                    double d = num(rv, "/");
                    if (d == 0) throw new EvalException("division by zero");
                    return num(lv, "/") / d;
                }
                case "%": {
                    double d = num(rv, "%");
                    if (d == 0) throw new EvalException("modulo by zero");
                    return num(lv, "%") % d;
                }
                case "==": return looseEquals(lv, rv);
                case "!=": return !looseEquals(lv, rv);
                case "<": return compare(lv, rv) < 0;
                case "<=": return compare(lv, rv) <= 0;
                case ">": return compare(lv, rv) > 0;
                case ">=": return compare(lv, rv) >= 0;
                default: throw new EvalException("bad operator " + op);
            }
        }

        private static boolean looseEquals(Object a, Object b) {
            if (a instanceof Number na && b instanceof Number nb) {
                return na.doubleValue() == nb.doubleValue();
            }
            return a == null ? b == null : a.equals(b);
        }

        private static int compare(Object a, Object b) {
            if (a instanceof Number na && b instanceof Number nb) {
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            if (a instanceof String sa && b instanceof String sb) return sa.compareTo(sb);
            throw new EvalException("cannot compare " + show(a) + " and " + show(b));
        }
    }

    // ---------- parser ----------

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        Expr parse() {
            Expr e = parseOr();
            skipWs();
            if (pos != s.length()) throw new EvalException("unexpected trailing input in expression '" + s + "'");
            return e;
        }

        private Expr parseOr() {
            Expr e = parseAnd();
            while (match("||")) e = new Bin("||", e, parseAnd());
            return e;
        }

        private Expr parseAnd() {
            Expr e = parseCmp();
            while (match("&&")) e = new Bin("&&", e, parseCmp());
            return e;
        }

        private Expr parseCmp() {
            Expr e = parseAdd();
            skipWs();
            for (String op : new String[] {"==", "!=", "<=", ">=", "<", ">"}) {
                if (match(op)) return new Bin(op, e, parseAdd());
            }
            return e;
        }

        private Expr parseAdd() {
            Expr e = parseMul();
            while (true) {
                if (match("+")) e = new Bin("+", e, parseMul());
                else if (matchSub()) e = new Bin("-", e, parseMul());
                else return e;
            }
        }

        private boolean matchSub() {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '-'
                    && !(pos + 1 < s.length() && s.charAt(pos + 1) == '-')) {
                pos++;
                return true;
            }
            return false;
        }

        private Expr parseMul() {
            Expr e = parseUnary();
            while (true) {
                if (match("*")) e = new Bin("*", e, parseUnary());
                else if (match("/")) e = new Bin("/", e, parseUnary());
                else if (match("%")) e = new Bin("%", e, parseUnary());
                else return e;
            }
        }

        private Expr parseUnary() {
            skipWs();
            if (match("!")) return new Un("!", parseUnary());
            if (match("-")) return new Un("-", parseUnary());
            return parsePostfix();
        }

        private Expr parsePostfix() {
            Expr e = parsePrimary();
            while (true) {
                skipWs();
                if (peek('.')) {
                    pos++;
                    e = new Field(e, ident());
                } else {
                    return e;
                }
            }
        }

        private Expr parsePrimary() {
            skipWs();
            if (pos >= s.length()) throw new EvalException("unexpected end of expression");
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Expr e = parseOr();
                skipWs();
                expect(')');
                return e;
            }
            if (c == '"' || c == '\'') return new Lit(string(c));
            if (Character.isDigit(c) || (c == '.' && pos + 1 < s.length() && Character.isDigit(s.charAt(pos + 1)))) {
                return new Lit(number());
            }
            if (Character.isLetter(c) || c == '_') {
                String name = ident();
                switch (name) {
                    case "true": return new Lit(Boolean.TRUE);
                    case "false": return new Lit(Boolean.FALSE);
                    case "null": return new Lit(null);
                    default:
                        skipWs();
                        if (peek('(')) {
                            pos++;
                            List<Expr> args = new ArrayList<>();
                            skipWs();
                            if (!peek(')')) {
                                do {
                                    args.add(parseOr());
                                    skipWs();
                                } while (match(","));
                            }
                            expect(')');
                            return new Call(name, args);
                        }
                        return new Var(name);
                }
            }
            throw new EvalException("unexpected character '" + c + "' in expression");
        }

        private String ident() {
            skipWs();
            int start = pos;
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) pos++;
            if (pos == start) throw new EvalException("expected identifier in expression '" + s + "'");
            return s.substring(start, pos);
        }

        private String string(char quote) {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == quote) return sb.toString();
                if (c == '\\' && pos < s.length()) {
                    char e = s.charAt(pos++);
                    sb.append(switch (e) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        default -> e;
                    });
                } else {
                    sb.append(c);
                }
            }
            throw new EvalException("unterminated string literal");
        }

        private Number number() {
            int start = pos;
            boolean dot = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) pos++;
                else if (c == '.' && !dot) { dot = true; pos++; }
                else break;
            }
            return Double.parseDouble(s.substring(start, pos));
        }

        private boolean match(String token) {
            skipWs();
            if (s.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        private boolean peek(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        private void expect(char c) {
            skipWs();
            if (!peek(c)) throw new EvalException("expected '" + c + "' in expression '" + s + "'");
            pos++;
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
