package com.kyvislabs.camera.gateway.events;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A trigger condition on a tag value, parsed once: {@code true}, {@code false}, {@code > n},
 * {@code >= n}, {@code < n}, {@code <= n}, {@code = v}, {@code != v}. Blank means "truthy"
 * (non-zero number, true boolean, non-empty string). Bad-quality values never match.
 */
public final class Condition {
    private static final Pattern CMP = Pattern.compile("^\\s*(>=|<=|!=|==|=|>|<)\\s*(.*?)\\s*$");

    private final String text;
    private final Predicate<Object> test;

    private Condition(String text, Predicate<Object> test) {
        this.text = text;
        this.test = test;
    }

    public static Condition parse(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return new Condition("truthy", Condition::truthy);
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.equals("true")) {
            return new Condition(t, Condition::truthy);
        }
        if (lower.equals("false")) {
            return new Condition(t, v -> v != null && !truthy(v));
        }
        Matcher m = CMP.matcher(t);
        if (!m.matches()) {
            throw new IllegalArgumentException("unrecognised condition '" + text + "'");
        }
        String op = m.group(1);
        String operand = unquote(m.group(2));
        Double num = number(operand);
        return new Condition(t, switch (op) {
            case ">" -> v -> num != null && number(v) != null && number(v) > num;
            case ">=" -> v -> num != null && number(v) != null && number(v) >= num;
            case "<" -> v -> num != null && number(v) != null && number(v) < num;
            case "<=" -> v -> num != null && number(v) != null && number(v) <= num;
            case "!=" -> v -> v != null && !equal(v, operand, num);
            default -> v -> v != null && equal(v, operand, num);
        });
    }

    public boolean test(Object value) {
        return test.test(value);
    }

    @Override
    public String toString() {
        return text;
    }

    private static boolean equal(Object v, String operand, Double num) {
        Double n = number(v);
        if (num != null && n != null) {
            return n.doubleValue() == num.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b == Boolean.parseBoolean(operand);
        }
        return String.valueOf(v).equals(operand);
    }

    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        String s = v.toString();
        return !s.isEmpty() && !s.equalsIgnoreCase("false") && !s.equals("0");
    }

    private static Double number(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1d : 0d;
        }
        if (v == null) {
            return null;
        }
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
