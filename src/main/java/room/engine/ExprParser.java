package room.engine;

import java.util.ArrayList;
import java.util.List;

public final class ExprParser {

    private final String s;
    private int pos;

    private ExprParser(String s) { this.s = s; }

    public static Expr parse(String text) {
        ExprParser p = new ExprParser(text);
        Expr e = p.parseExpr(0);
        p.ws();
        if (!p.eof()) throw new EngineException("表达式解析失败: '" + text + "' 位置 " + p.pos + " 有多余字符");
        return e;
    }

    private boolean eof() { return pos >= s.length(); }

    private void ws() {
        while (!eof() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    private char peek() { return s.charAt(pos); }

    private boolean kw(String word) {
        ws();
        if (s.regionMatches(pos, word, 0, word.length())
                && (pos + word.length() == s.length()
                    || !Character.isLetterOrDigit(s.charAt(pos + word.length())))
                && (pos == 0 || !Character.isLetterOrDigit(s.charAt(pos - 1)))) {
            pos += word.length();
            return true;
        }
        return false;
    }

    private boolean symbol(String sym) {
        ws();
        if (s.startsWith(sym, pos)) {
            pos += sym.length();
            return true;
        }
        return false;
    }

    private void expect(char c) {
        ws();
        if (eof() || s.charAt(pos) != c)
            throw new EngineException("表达式解析失败: 期望 '" + c + "'，位于 '" + s + "' 位置 " + pos);
        pos++;
    }

    private Expr parseExpr(int minPrec) {
        Expr left = parseUnary();
        while (true) {
            ws();
            if (eof()) break;
            String op = readOp();
            if (op == null) break;
            int prec = precOf(op);
            if (prec < minPrec) break;
            pos += op.length();
            Expr right = parseExpr(prec + 1);
            left = new Expr.Bin(op, left, right);
        }
        ws();
        if (minPrec == 0 && (symbol("and") || symbol("or"))) {
            // 不会走到这里：and/or 在 readOp 内处理
        }
        return left;
    }

    private String readOp() {
        ws();
        if (eof()) return null;
        for (String op : new String[]{"==", "!=", ">=", "<=", "+", "-", "*", "/", "%", ">", "<"}) {
            if (s.startsWith(op, pos)) return op;
        }
        int start = pos;
        if (kwAt("and")) return "and";
        if (kwAt("or")) return "or";
        pos = start;
        return null;
    }

    private boolean kwAt(String word) {
        return s.regionMatches(pos, word, 0, word.length())
                && (pos + word.length() == s.length()
                    || !Character.isLetterOrDigit(s.charAt(pos + word.length())));
    }

    private int precOf(String op) {
        return switch (op) {
            case "or" -> 1;
            case "and" -> 2;
            case "==", "!=" -> 3;
            case ">", ">=", "<", "<=" -> 4;
            case "+", "-" -> 5;
            case "*", "/", "%" -> 6;
            default -> 0;
        };
    }

    private Expr parseUnary() {
        ws();
        if (symbol("-")) return new Expr.Un("-", parseUnary());
        if (kw("not")) return new Expr.Un("not", parseUnary());
        return parsePrimary();
    }

    private Expr parsePrimary() {
        ws();
        if (eof()) throw new EngineException("表达式意外结束: " + s);
        char c = peek();
        if (c == '(') {
            pos++;
            Expr e = parseExpr(0);
            expect(')');
            return e;
        }
        if (c == '"' || c == '\'') return new Expr.Lit(readString(c));
        if (Character.isDigit(c)) return readNumber();
        if (c == '$') return readPath(true);
        if (c == '@') return readPath(false);
        if (Character.isLetter(c) || c == '_') return readWord();
        throw new EngineException("表达式解析失败: 非法字符 '" + c + "' 在 '" + s + "'");
    }

    private Expr readPath(boolean statePrefix) {
        pos++; // 跳过 $ 或 @
        int start = pos;
        while (!eof()) {
            char c = peek();
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '[') pos++;
            else break;
        }
        String path = s.substring(start, pos).replaceAll("\\[|\\]", "");
        if (path.endsWith(".")) path = path.substring(0, path.length() - 1);
        return new Expr.Path(statePrefix, path);
    }

    private Expr readNumber() {
        int start = pos;
        boolean dot = false;
        while (!eof()) {
            char c = peek();
            if (Character.isDigit(c)) pos++;
            else if (c == '.' && !dot) { dot = true; pos++; }
            else break;
        }
        String t = s.substring(start, pos);
        return new Expr.Lit(dot ? (Object) Double.parseDouble(t) : (Object) Long.parseLong(t));
    }

    private String readString(char quote) {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (!eof() && peek() != quote) {
            char c = s.charAt(pos++);
            if (c == '\\' && !eof()) {
                char n = s.charAt(pos++);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    default -> sb.append(n);
                }
            } else sb.append(c);
        }
        if (eof()) throw new EngineException("字符串未闭合: " + s);
        pos++;
        return sb.toString();
    }

    private Expr readWord() {
        int start = pos;
        while (!eof() && (Character.isLetterOrDigit(peek()) || peek() == '_')) pos++;
        String word = s.substring(start, pos);
        if (word.equals("true")) return new Expr.Lit(Boolean.TRUE);
        if (word.equals("false")) return new Expr.Lit(Boolean.FALSE);
        if (word.equals("null")) return new Expr.Lit(null);
        ws();
        if (!eof() && peek() == '(') {
            pos++;
            List<Expr> args = new ArrayList<>();
            ws();
            if (!eof() && peek() != ')') {
                args.add(parseExpr(0));
                while (symbol(",")) args.add(parseExpr(0));
            }
            expect(')');
            return new Expr.Call(word, args);
        }
        throw new EngineException("表达式中的未识别标识 " + word + "（路径请用 $ 前缀，事件字段用 @ 前缀）");
    }
}
