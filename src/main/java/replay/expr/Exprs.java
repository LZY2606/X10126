package replay.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 小型确定性表达式语言。
 * 支持：整数/小数/字符串/布尔字面量，变量（含 data.x 访问事件数据），state，
 * 算术 + - * / %，比较 == != < <= > >=，逻辑 && || !，括号，以及 rand(n)。
 * 求值不依赖任何挂钟时间；rand 由调用方注入的种子化随机源驱动。
 */
public final class Exprs {

    private Exprs() {}

    public static final class EvalException extends RuntimeException {
        public EvalException(String msg) { super(msg); }
    }

    /** 求值上下文：变量表、当前状态、事件数据、随机源。 */
    public interface Context {
        Object lookup(String name);
        long nextRand(long bound);
    }

    public static Object eval(String source, Context ctx) {
        Parser p = new Parser(source, ctx);
        Object v = p.parseOr();
        p.skipWs();
        if (!p.atEnd()) throw new EvalException("表达式存在多余内容: " + source);
        return v;
    }

    public static boolean evalBool(String source, Context ctx) {
        return toBool(eval(source, ctx));
    }

    public static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    private static final class Parser {
        private final String s;
        private final Context ctx;
        private int pos;

        Parser(String s, Context ctx) { this.s = s; this.ctx = ctx; }

        boolean atEnd() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private boolean eat(String op) {
            skipWs();
            if (s.startsWith(op, pos)) {
                // 防止把 "<=" 吃成 "<"
                pos += op.length();
                return true;
            }
            return false;
        }

        private boolean eatWord(String w) {
            skipWs();
            if (s.startsWith(w, pos)) {
                int end = pos + w.length();
                if (end >= s.length() || !isIdentChar(s.charAt(end))) {
                    pos = end;
                    return true;
                }
            }
            return false;
        }

        Object parseOr() {
            Object left = parseAnd();
            while (true) {
                if (eat("||")) left = toBool(left) || toBool(parseAnd());
                else return left;
            }
        }

        private Object parseAnd() {
            Object left = parseCmp();
            while (true) {
                if (eat("&&")) left = toBool(left) && toBool(parseCmp());
                else return left;
            }
        }

        private Object parseCmp() {
            Object left = parseAdd();
            skipWs();
            if (eat("==")) return compare(left, parseAdd(), "==");
            if (eat("!=")) return compare(left, parseAdd(), "!=");
            if (eat("<=")) return compare(left, parseAdd(), "<=");
            if (eat(">=")) return compare(left, parseAdd(), ">=");
            if (eat("<")) return compare(left, parseAdd(), "<");
            if (eat(">")) return compare(left, parseAdd(), ">");
            return left;
        }

        private Object parseAdd() {
            Object left = parseMul();
            while (true) {
                if (eat("+")) left = add(left, parseMul());
                else if (eat("-")) left = sub(left, parseMul());
                else return left;
            }
        }

        private Object parseMul() {
            Object left = parseUnary();
            while (true) {
                if (eat("*")) left = mul(left, parseUnary());
                else if (eat("/")) left = div(left, parseUnary());
                else if (eat("%")) left = mod(left, parseUnary());
                else return left;
            }
        }

        private Object parseUnary() {
            if (eat("!")) return !toBool(parseUnary());
            if (eat("-")) return neg(parseUnary());
            return parsePrimary();
        }

        private Object parsePrimary() {
            skipWs();
            if (atEnd()) throw new EvalException("表达式意外结束");
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Object v = parseOr();
                skipWs();
                if (atEnd() || s.charAt(pos) != ')') throw new EvalException("缺少 ')'");
                pos++;
                return v;
            }
            if (c == '"' || c == '\'') return parseString(c);
            if (Character.isDigit(c)) return parseNumber();
            if (isIdentStart(c)) return parseIdent();
            throw new EvalException("第 " + pos + " 个字符附近无法解析: " + s);
        }

        private String parseString(char quote) {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw new EvalException("字符串未闭合");
                char c = s.charAt(pos++);
                if (c == quote) return sb.toString();
                if (c == '\\' && !atEnd()) {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
        }

        private Object parseNumber() {
            int start = pos;
            boolean floating = false;
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) pos++;
                else if (c == '.') { floating = true; pos++; }
                else break;
            }
            String num = s.substring(start, pos);
            try {
                return floating ? (Object) Double.parseDouble(num) : Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new EvalException("非法数值: " + num);
            }
        }

        private Object parseIdent() {
            int start = pos;
            while (!atEnd() && isIdentChar(s.charAt(pos))) pos++;
            String name = s.substring(start, pos);
            skipWs();
            if (!atEnd() && s.charAt(pos) == '(') {
                // 函数调用
                pos++;
                List<Object> args = new ArrayList<>();
                skipWs();
                if (!atEnd() && s.charAt(pos) == ')') { pos++; }
                else {
                    while (true) {
                        args.add(parseOr());
                        skipWs();
                        if (!atEnd() && s.charAt(pos) == ',') { pos++; continue; }
                        if (!atEnd() && s.charAt(pos) == ')') { pos++; break; }
                        throw new EvalException("函数参数缺少 ')'");
                    }
                }
                return call(name, args);
            }
            switch (name) {
                case "true": return Boolean.TRUE;
                case "false": return Boolean.FALSE;
                case "null": return null;
                default: {
                    Object v = ctx.lookup(name);
                    if (v == null && !name.contains(".")) {
                        // 未定义变量按 null 处理，便于条件里引用可选字段
                    }
                    return v;
                }
            }
        }

        private Object call(String name, List<Object> args) {
            if (name.equals("rand")) {
                if (args.size() != 1) throw new EvalException("rand 需要 1 个参数");
                long bound = toLong(args.get(0));
                if (bound <= 0) throw new EvalException("rand 的参数必须为正数");
                return ctx.nextRand(bound);
            }
            throw new EvalException("未知函数: " + name);
        }

        private static boolean isIdentStart(char c) {
            return Character.isLetter(c) || c == '_';
        }

        private static boolean isIdentChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '.';
        }
    }

    // ---------- 运算 ----------

    static long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        throw new EvalException("期望数值，得到: " + v);
    }

    private static double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        throw new EvalException("期望数值，得到: " + v);
    }

    private static boolean bothNumbers(Object a, Object b) {
        return a instanceof Number && b instanceof Number;
    }

    private static boolean integral(Object a, Object b) {
        return a instanceof Long && b instanceof Long;
    }

    static Object neg(Object a) {
        if (a instanceof Long) return -((Long) a);
        if (a instanceof Number) return -((Number) a).doubleValue();
        throw new EvalException("无法取负: " + a);
    }

    static Object add(Object a, Object b) {
        if (a instanceof String || b instanceof String) return str(a) + str(b);
        if (bothNumbers(a, b)) {
            if (integral(a, b)) return (Long) a + (Long) b;
            return toDouble(a) + toDouble(b);
        }
        throw new EvalException("无法相加: " + a + " + " + b);
    }

    static Object sub(Object a, Object b) {
        if (bothNumbers(a, b)) {
            if (integral(a, b)) return (Long) a - (Long) b;
            return toDouble(a) - toDouble(b);
        }
        throw new EvalException("无法相减");
    }

    static Object mul(Object a, Object b) {
        if (bothNumbers(a, b)) {
            if (integral(a, b)) return (Long) a * (Long) b;
            return toDouble(a) * toDouble(b);
        }
        throw new EvalException("无法相乘");
    }

    static Object div(Object a, Object b) {
        if (bothNumbers(a, b)) {
            if (integral(a, b)) {
                long d = (Long) b;
                if (d == 0) throw new EvalException("除以零");
                return (Long) a / d;
            }
            double d = toDouble(b);
            if (d == 0) throw new EvalException("除以零");
            return toDouble(a) / d;
        }
        throw new EvalException("无法相除");
    }

    static Object mod(Object a, Object b) {
        if (bothNumbers(a, b)) {
            long d = toLong(b);
            if (d == 0) throw new EvalException("对零取模");
            return toLong(a) % d;
        }
        throw new EvalException("无法取模");
    }

    static Object compare(Object a, Object b, String op) {
        if (op.equals("==")) return looseEquals(a, b);
        if (op.equals("!=")) return !looseEquals(a, b);
        int c;
        if (bothNumbers(a, b)) {
            c = Double.compare(toDouble(a), toDouble(b));
        } else if (a instanceof String && b instanceof String) {
            c = ((String) a).compareTo((String) b);
        } else {
            throw new EvalException("无法比较: " + a + " 与 " + b);
        }
        switch (op) {
            case "<": return c < 0;
            case "<=": return c <= 0;
            case ">": return c > 0;
            case ">=": return c >= 0;
            default: throw new EvalException("未知比较符 " + op);
        }
    }

    static boolean looseEquals(Object a, Object b) {
        if (bothNumbers(a, b)) return Double.compare(toDouble(a), toDouble(b)) == 0;
        return Objects.equals(a, b);
    }

    static String str(Object v) {
        return v == null ? "null" : v.toString();
    }
}
