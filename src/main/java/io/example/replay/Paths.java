package io.example.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Paths {
    private Paths() {
    }

    public static Object get(Object root, String path) {
        List<Token> tokens = parse(path);
        Object current = root;
        for (Token token : tokens) {
            if (current == null) {
                return null;
            }
            if (token.mapKey != null) {
                if (!(current instanceof Map<?, ?> map)) {
                    return null;
                }
                current = map.get(token.mapKey);
            } else {
                if (!(current instanceof List<?> list) || token.index < 0 || token.index >= list.size()) {
                    return null;
                }
                current = list.get(token.index);
            }
        }
        return current;
    }

    public static void set(Object root, String tail, Object value) {
        if (tail == null) {
            throw new IllegalArgumentException("Cannot replace path root");
        }
        List<Token> tokens = parse("$." + tail);
        setParsed(root, tokens, value);
    }

    public static void remove(Object root, String tail) {
        if (tail == null) {
            throw new IllegalArgumentException("Cannot remove path root");
        }
        List<Token> tokens = parse("$." + tail);
        if (tokens.size() < 2) {
            throw new IllegalArgumentException("Cannot remove root");
        }
        Object parent = navigateParent(root, tokens);
        Token last = tokens.get(tokens.size() - 1);
        if (last.mapKey != null) {
            ((Map<?, ?>) parent).remove(last.mapKey);
        }
    }

    private static void setParsed(Object root, List<Token> tokens, Object value) {
        Object current = navigateParent(root, tokens);
        Token last = tokens.get(tokens.size() - 1);
        if (last.mapKey != null) {
            asWritableMap(current).put(last.mapKey, value);
        } else {
            List<Object> list = asWritableList(current);
            while (list.size() <= last.index) {
                list.add(null);
            }
            list.set(last.index, value);
        }
    }

    private static Object navigateParent(Object root, List<Token> tokens) {
        Object current = root;
        for (int i = 1; i < tokens.size() - 1; i++) {
            Token token = tokens.get(i);
            if (token.mapKey != null) {
                Map<String, Object> map = asWritableMap(current);
                current = map.computeIfAbsent(token.mapKey, ignored -> new LinkedHashMap<>());
            } else {
                List<Object> list = asWritableList(current);
                while (list.size() <= token.index) {
                    list.add(new LinkedHashMap<>());
                }
                current = list.get(token.index);
                if (current == null) {
                    current = new LinkedHashMap<>();
                    list.set(token.index, current);
                }
            }
        }
        return current;
    }

    private static Map<String, Object> asWritableMap(Object value) {
        return Json.object(value);
    }

    private static List<Object> asWritableList(Object value) {
        return Json.list(value);
    }

    private static List<Token> parse(String path) {
        List<Token> tokens = new ArrayList<>();
        if (path.equals("$")) {
            tokens.add(Token.map("$"));
            return tokens;
        }
        if (path.startsWith("$.")) {
            tokens.add(Token.map("$"));
            path = path.substring(1);
        } else if (path.startsWith("$[")) {
            tokens.add(Token.map("$"));
            path = path.substring(1);
        }
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                if (key.length() > 0) {
                    tokens.add(Token.map(key.toString()));
                    key.setLength(0);
                }
            } else if (c == '[') {
                if (key.length() > 0) {
                    tokens.add(Token.map(key.toString()));
                    key.setLength(0);
                }
                int end = path.indexOf(']', i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("Bad path: " + path);
                }
                tokens.add(Token.index(Integer.parseInt(path.substring(i + 1, end))));
                i = end;
            } else {
                key.append(c);
            }
        }
        if (key.length() > 0) {
            tokens.add(Token.map(key.toString()));
        }
        return tokens;
    }

    private record Token(String mapKey, Integer index) {
        static Token map(String key) {
            return new Token(key, null);
        }

        static Token index(int index) {
            return new Token(null, index);
        }
    }
}
