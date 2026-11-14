package io.internetdata.middleware;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.internetdata.Result;
import io.internetdata.internal.ApiClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link Result} as the wire described it, for a condition to match against.
 *
 * <p>Read off the answer as it was served, under its own names, so a member the spec adds reaches a
 * condition with no list here to keep in step: a projection built accessor by accessor once left
 * {@code location} and {@code asn} out, and a country condition matched nothing. A member the plan
 * does not include is null in the model and has to come out as an ABSENT key, so nulls are dropped
 * here, at every depth, rather than left to a serializer's inclusion setting.
 */
final class Served {
    // The SDK's own mapper, not a bare one: the one that read the answer is the only one whose names
    // and types this projection cannot disagree with. It writes every null, so dropping them is
    // this class's alone.
    private static final ObjectMapper MAPPER = ApiClient.createDefaultObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS);

    private Served() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> of(Result result) {
        return (Map<String, Object>) present(MAPPER.convertValue(result.raw(), Map.class));
    }

    private static Object present(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, entry) -> {
                if (entry != null) {
                    out.put(String.valueOf(key), present(entry));
                }
            });
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object entry : list) {
                out.add(present(entry));
            }
            return out;
        }
        return value;
    }
}
