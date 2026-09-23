package replay.core;

import java.util.List;

/** A single parsed action inside a rule. */
public abstract class Action {

    public abstract void apply(Engine.Frame frame, Engine engine, List<EventInstance> emitted,
                               List<String> outputs) throws ActionFailure;

    public static final class ActionFailure extends Exception {
        public ActionFailure(String msg) { super(msg); }
    }

    /** Parse an action string, validating goto targets against known states. */
    public static Action parse(String src, List<String> knownStates) {
        String s = src.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("empty action");
        if (s.startsWith("goto ")) {
            String target = s.substring(5).trim();
            if (!knownStates.contains(target))
                throw new IllegalArgumentException("goto targets unknown state '" + target + "'");
            return new Goto(target);
        }
        if (s.startsWith("set ")) {
            int eq = s.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("set action needs '=': " + src);
            String var = s.substring(4, eq).trim();
            if (!var.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("bad variable name in: " + src);
            String expr = s.substring(eq + 1).trim();
            return new Set(var, expr);
        }
        if (s.startsWith("emit ")) {
            String rest = s.substring(5).trim();
            String type;
            String delayExpr = null;
            int afterIdx = indexOfWord(rest, "after");
            if (afterIdx >= 0) {
                type = rest.substring(0, afterIdx).trim();
                delayExpr = rest.substring(afterIdx + 5).trim();
            } else {
                type = rest;
            }
            if (!type.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("bad event type in: " + src);
            return new Emit(type, delayExpr);
        }
        if (s.startsWith("output ")) {
            return new Output(s.substring(7).trim());
        }
        if (s.equals("fail") || s.startsWith("fail ")) {
            String msg = s.length() > 5 ? s.substring(5).trim() : "explicit fail action";
            return new Fail(msg);
        }
        throw new IllegalArgumentException("unknown action: " + src);
    }

    private static int indexOfWord(String s, String word) {
        int idx = 0;
        while ((idx = s.indexOf(word, idx)) >= 0) {
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(s.charAt(idx - 1));
            int end = idx + word.length();
            boolean rightOk = end >= s.length() || !Character.isLetterOrDigit(s.charAt(end));
            if (leftOk && rightOk) return idx;
            idx = end;
        }
        return -1;
    }

    static final class Goto extends Action {
        final String target;
        Goto(String target) { this.target = target; }
        @Override public void apply(Engine.Frame frame, Engine engine, List<EventInstance> emitted,
                                    List<String> outputs) {
            frame.state = target;
        }
    }

    static final class Set extends Action {
        final String var;
        final String expr;
        Set(String var, String expr) { this.var = var; this.expr = expr; }
        @Override public void apply(Engine.Frame frame, Engine engine, List<EventInstance> emitted,
                                    List<String> outputs) throws ActionFailure {
            try {
                frame.vars.put(var, Expr.eval(expr, engine.evalContext(frame)));
            } catch (Expr.EvalException e) {
                throw new ActionFailure("set " + var + " failed: " + e.getMessage());
            }
        }
    }

    static final class Emit extends Action {
        final String type;
        final String delayExpr;
        Emit(String type, String delayExpr) { this.type = type; this.delayExpr = delayExpr; }
        @Override public void apply(Engine.Frame frame, Engine engine, List<EventInstance> emitted,
                                    List<String> outputs) throws ActionFailure {
            long delay = 0;
            if (delayExpr != null) {
                try {
                    delay = Expr.asLong(Expr.eval(delayExpr, engine.evalContext(frame)));
                } catch (Expr.EvalException e) {
                    throw new ActionFailure("emit delay failed: " + e.getMessage());
                }
                if (delay < 0) throw new ActionFailure("emit delay must be >= 0");
            }
            emitted.add(engine.newInternalEvent(type, delay));
        }
    }

    static final class Output extends Action {
        final String expr;
        Output(String expr) { this.expr = expr; }
        @Override public void apply(Engine.Frame frame, Engine engine, List<EventInstance> emitted,
                                    List<String> outputs) throws ActionFailure {
            try {
                outputs.add(Expr.asString(Expr.eval(expr, engine.evalContext(frame))));
            } catch (Expr.EvalException e) {
                throw new ActionFailure("output failed: " + e.getMessage());
            }
        }
    }

    static final class Fail extends Action {
        final String message;
        Fail(String message) { this.message = message; }
        @Override public void apply(Engine.Frame frame, Engine engine, List<EventInstance> emitted,
                                    List<String> outputs) throws ActionFailure {
            throw new ActionFailure(message);
        }
    }
}
