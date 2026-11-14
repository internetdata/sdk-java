package io.internetdata.middleware;

import io.internetdata.Result;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deciding whether an answer is worth blocking.
 *
 * <p>A condition is written in the shape of a served answer and keyed by the same names the API
 * uses, so what you write here reads like what you get back:
 *
 * <pre>{@code
 * Map.of("location", Map.of("country", List.of("CU", "IR", "KP")))
 * Map.of("asn", Map.of("type", "hosting"))
 * Map.of("is_vpn", true, "vpn", Map.of("provider", "nordvpn"))
 * Map.of("is_resproxy", true, "resproxy", Map.of("hits", Bound.gte(5)))
 * }</pre>
 *
 * <p>A value may be a scalar (equality, strings without regard to case), a {@link Collection}
 * meaning any-of, a {@link Bound} comparing a number, or a nested condition. A member set to
 * {@code false} or {@code null} is ignored entirely - a condition states the positive signals you
 * act on, so there is no way to write "block when this is false", which would otherwise read as
 * blocking everybody.
 */
public final class Condition {
    private Condition() {}

    /** Whether an answer satisfies the condition, and should therefore be blocked. */
    public static boolean matches(List<Map<String, Object>> conditions, Result result) {
        Map<String, Object> served = Served.of(result);
        for (Map<String, Object> one : conditions) {
            if (matchesObject(one, served)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The members a condition names that this answer did not carry: a top-level member, or a paid
     * one inside an object every plan gets, by its path ({@code location.city}).
     *
     * <p>A field your plan does not include is absent rather than false, so a condition naming one
     * can never match and the block would silently never fire. An empty object is a real answer,
     * meaning the flag above it is false, so nothing inside one is reported: {@code vpn} present but
     * {@code {}} is not a plan gap. A populated object carries every member the plan includes, so a
     * member missing there is one.
     *
     * <p>A locally answered bogon needs no special case: it is synthesized in the widest shape, so
     * every member is present and nothing reads as missing.
     */
    public static List<String> missingMembers(List<Map<String, Object>> conditions, Result result) {
        Map<String, Object> served = Served.of(result);
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> one : conditions) {
            collectMissing(one, served, "", missing);
        }
        return missing;
    }

    /**
     * Refuse a condition that constrains nothing.
     *
     * <p>Ignoring {@code false} means {@code {"is_vpn": false}} and an empty map have no terms left
     * to satisfy, so they would match every answer and block all traffic. Nobody writes that on
     * purpose, and failing when the middleware is built beats discovering it in production.
     */
    public static void validate(List<Map<String, Object>> conditions) {
        if (conditions == null) {
            return;
        }
        for (Map<String, Object> one : conditions) {
            if (constraintCount(one) == 0) {
                throw new IllegalArgumentException(
                        "internetdata: block condition " + one + " constrains nothing, which would "
                                + "block every request; a member set to false or null is ignored, "
                                + "so state the positive signals you act on");
            }
        }
    }

    /** How many leaf constraints a condition actually carries. */
    public static int constraintCount(Object condition) {
        if (condition == null || Boolean.FALSE.equals(condition)) {
            return 0;
        }
        if (condition instanceof Bound) {
            return 1;
        }
        if (condition instanceof Map<?, ?> map) {
            int n = 0;
            for (Object value : map.values()) {
                n += constraintCount(value);
            }
            return n;
        }
        if (condition instanceof Collection<?> collection) {
            int n = 0;
            for (Object value : collection) {
                n += constraintCount(value);
            }
            return n;
        }
        return 1;
    }

    private static void collectMissing(
            Map<String, Object> condition, Map<?, ?> served, String prefix, List<String> missing) {
        for (Map.Entry<String, Object> entry : condition.entrySet()) {
            Object want = entry.getValue();
            if (constraintCount(want) == 0) {
                continue;
            }
            String path = prefix + entry.getKey();
            if (!served.containsKey(entry.getKey())) {
                if (!missing.contains(path)) {
                    missing.add(path);
                }
                continue;
            }
            Object got = served.get(entry.getKey());
            if (want instanceof Map<?, ?> nested && got instanceof Map<?, ?> object && !isEmpty(object)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) nested;
                collectMissing(typed, object, path + ".", missing);
            }
        }
    }

    // The generator starts a model's optional list empty, so a detail object served as {} comes
    // back holding one: an object is empty when it holds nothing else.
    private static boolean isEmpty(Map<?, ?> object) {
        for (Object value : object.values()) {
            if (!(value instanceof Collection<?> list && list.isEmpty())) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesObject(Map<String, Object> condition, Object value) {
        if (!(value instanceof Map<?, ?> served)) {
            return false;
        }
        for (Map.Entry<String, Object> entry : condition.entrySet()) {
            Object want = entry.getValue();
            if (constraintCount(want) == 0) {
                continue;
            }
            if (!matchesValue(want, served.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether one value satisfies one want.
     *
     * <p>An ABSENT member arrives here as {@code null}, which is exactly what "not in your plan"
     * looks like. Every branch below must therefore reject it, which is what makes an unserved
     * member fail a match rather than pass it.
     */
    private static boolean matchesValue(Object want, Object got) {
        if (want instanceof Bound bound) {
            return bound.matches(got);
        }
        if (want instanceof Collection<?> anyOf) {
            for (Object entry : anyOf) {
                if (matchesValue(entry, got)) {
                    return true;
                }
            }
            return false;
        }
        if (want instanceof Map<?, ?> nested) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) nested;
            return matchesObject(typed, got);
        }
        if (want instanceof String text) {
            // Providers are lowercase slugs and countries uppercase codes on the wire, and a caller
            // should not have to know which, so a string compares without case.
            return got instanceof String other
                    && text.toLowerCase(Locale.ROOT).equals(other.toLowerCase(Locale.ROOT));
        }
        if (want instanceof Number number) {
            return got instanceof Number other
                    && Double.compare(number.doubleValue(), other.doubleValue()) == 0;
        }
        return want.equals(got);
    }

    /** The key set a {@link Bound} is built from, for a caller reading a condition back. */
    public static final Set<String> BOUND_KEYS = Set.of("gte", "gt", "lte", "lt");

    /** A condition as a plain map, for building one from parsed JSON. */
    public static Map<String, Object> of(Map<String, Object> entries) {
        return new LinkedHashMap<>(entries);
    }
}
