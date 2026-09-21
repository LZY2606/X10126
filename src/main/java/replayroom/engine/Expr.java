package replayroom.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Small deterministic expression language for conditions and action values.
 *
 * Precedence: logical-or, logical-and, comparison, additive, multiplicative, unary, postfix.
 * Values: booleans, numbers, strings, null, maps and lists.
 */
public final class Expr {

    public static final class EvalException extends RuntimeException {
        public EvalException(String message) { super(message); }
    }

    interface Node {
        Object eval(Eval ctx);
    }

    static final class Eval {
        final Map<String, Object> root;
        final DeterministicRng rng;
        Eval(Map<String, Object> root, DeterministicRng rng) {
            this.root = root;
            this.rng = rng;
        }
    }

    private final String source;
    private final Node root;

    private Expr(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    public static Expr compile(String source) {
        if (source == null || source.isBlank()) throw new EvalException("empty expression");
        return new Expr(source, new ExprParser(source).parse());
    }

    public Object eval(Map<String, Object> context, DeterministicRng rng) {
        return root.eval(new Eval(context, rng));
    }

    public boolean evalBoolean(Map<String, Object> context, DeterministicRng rng) {
        return truthy(root.eval(new Eval(context, rng)));
    }

    public String sourceText() { return source; }

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        if (v instanceof String str) return !str.isEmpty();
        return true;
    }

    static double asDouble(Object v, String what) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1.0 : 0.0;
        if (v instanceof String str) {
            try { return Double.parseDouble(str.trim()); }
            catch (NumberFormatException e) { throw new EvalException(what + " is not a number: " + str); }
        }
        throw new EvalException(what + " is not a number");
    }
}
