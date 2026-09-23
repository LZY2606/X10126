package replay.expr;

/** String rendering shared by output templates. */
public final class ExprStr {
    private ExprStr() {}

    public static String of(Object v) {
        if (v == null) return "null";
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        }
        return v.toString();
    }
}
