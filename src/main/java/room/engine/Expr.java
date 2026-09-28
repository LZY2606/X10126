package room.engine;

import java.util.List;
import java.util.Map;

/**
 * 小型确定性表达式：字面量、state/event 路径、算术/比较/逻辑运算、if。
 * 不做隐式数字到布尔转换；truth 仅接受布尔或 null 安全判断。
 */
public interface Expr {

    Object eval(EvalContext ctx);

    record Lit(Object value) implements Expr {
        @Override public Object eval(EvalContext ctx) { return value; }
    }

    record Path(boolean statePrefix, String path) implements Expr {
        @Override public Object eval(EvalContext ctx) {
            Object root = statePrefix ? ctx.state() : ctx.event();
            return room.util.Json.getPath(root, path);
        }
    }

    record Un(String op, Expr a) implements Expr {
        @Override public Object eval(EvalContext ctx) {
            Object x = a.eval(ctx);
            return switch (op) {
                case "-" -> -num(x);
                case "not" -> !truth(x);
                default -> throw new EngineException("未知一元运算符 " + op);
            };
        }
    }

    record Bin(String op, Expr a, Expr b) implements Expr {
        @Override public Object eval(EvalContext ctx) {
            Object x = a.eval(ctx);
            Object y = b.eval(ctx);
            return switch (op) {
                case "+" -> add(x, y);
                case "-" -> num(x) - num(y);
                case "*" -> num(x) * num(y);
                case "/" -> div(x, y);
                case "%" -> remain(x, y);
                case "==" -> equalsVal(x, y);
                case "!=" -> !equalsVal(x, y);
                case ">" -> num(x) > num(y);
                case ">=" -> num(x) >= num(y);
                case "<" -> num(x) < num(y);
                case "<=" -> num(x) <= num(y);
                case "and" -> truth(x) && truth(y);
                case "or" -> truth(x) || truth(y);
                default -> throw new EngineException("未知运算符 " + op);
            };
        }
    }

    record Call(String name, List<Expr> args) implements Expr {
        @Override public Object eval(EvalContext ctx) {
            return switch (name) {
                case "if" -> {
                    if (args.size() != 3) throw new EngineException("if 需要 3 个参数");
                    yield truth(args.get(0).eval(ctx)) ? args.get(1).eval(ctx) : args.get(2).eval(ctx);
                }
                case "abs" -> Math.abs(num(args.get(0).eval(ctx)));
                case "min", "max" -> {
                    double r = num(args.get(0).eval(ctx));
                    for (int i = 1; i < args.size(); i++) {
                        double v = num(args.get(i).eval(ctx));
                        r = name.equals("min") ? Math.min(r, v) : Math.max(r, v);
                    }
                    yield (long) r == r ? (Object) (long) r : (Object) r;
                }
                default -> throw new EngineException("未知函数 " + name);
            };
        }
    }

    record EvalContext(Map<String, Object> state, Map<String, Object> event) {}

    static double num(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Double d) return d;
        if (v == null) throw new EngineException("null 不能当作数字");
        throw new EngineException("类型 " + room.util.Json.typeName(v) + " 不能当作数字");
    }

    static Object add(Object x, Object y) {
        if (x instanceof String || y instanceof String) return String.valueOf(x) + String.valueOf(y);
        if (x instanceof Long && y instanceof Long) return (long) (((Long) x) + (Long) y);
        return num(x) + num(y);
    }

    static Object div(Object x, Object y) {
        double b = num(y);
        double r = num(x) / b;
        if (x instanceof Long && y instanceof Long && ((Long) y) != 0 && ((Long) x) % (Long) y == 0) {
            return (long) (((Long) x) / (Long) y);
        }
        return r;
    }

    static Object remain(Object x, Object y) {
        if (x instanceof Long && y instanceof Long) return (long) (((Long) x) % (Long) y);
        return num(x) % num(y);
    }

    static boolean truth(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        throw new EngineException("类型 " + room.util.Json.typeName(v) + " 不能当作布尔条件");
    }

    static boolean equalsVal(Object x, Object y) {
        if (x instanceof Number && y instanceof Number) return num(x) == num(y);
        if (x == null) return y == null;
        return x.equals(y);
    }
}
