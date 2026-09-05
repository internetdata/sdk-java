package io.internetdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.internetdata.internal.ApiException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * The seam between the generated wire layer and this one: retries, and the generated
 * {@link ApiException} turned into an {@link InternetDataException}.
 */
final class Wire {
    private Wire() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration BACKOFF_BASE = Duration.ofMillis(200);
    private static final Duration BACKOFF_CAP = Duration.ofSeconds(8);

    interface Call<T> {
        T invoke() throws ApiException;
    }

    /** One attempt that reports its own failure, for a request the generated layer does not make. */
    interface Attempt<T> {
        T invoke();
    }

    /** Runs a generated call, retrying a transient failure up to {@code retries} times. */
    static <T> T execute(int retries, Call<T> call) {
        return retrying(retries, () -> {
            try {
                return call.invoke();
            } catch (ApiException e) {
                throw translate(e);
            }
        });
    }

    /**
     * Retries a transient failure up to {@code retries} times.
     *
     * <p>A server-supplied {@code Retry-After} wins over the backoff schedule, and is also the only
     * thing that makes a 429 retryable at all.
     */
    static <T> T retrying(int retries, Attempt<T> attempt) {
        for (int i = 0; ; i++) {
            InternetDataException failure;
            try {
                return attempt.invoke();
            } catch (InternetDataException e) {
                failure = e;
            }
            if (i >= retries || !failure.retryable()) {
                throw failure;
            }
            Duration asked = failure.retryAfter().orElse(null);
            sleep(asked != null ? asked : backoff(i));
        }
    }

    static InternetDataException translate(ApiException e) {
        int status = e.getCode();
        if (status == 0) {
            return new InternetDataException(ErrorKind.NETWORK, messageOf(e), null, null, e.getCause());
        }
        Duration retryAfter = retryAfterOf(e);
        return new InternetDataException(kindOf(status, retryAfter), messageOf(e), status, retryAfter, null);
    }

    /**
     * What an HTTP status means, for the generated calls and for the raw database transfer alike.
     *
     * @param retryAfter the response's {@code Retry-After}, which is the only thing separating a
     *     rate limit from a spent allowance.
     */
    static ErrorKind kindOf(int status, Duration retryAfter) {
        switch (status) {
            case 400:
                return ErrorKind.BAD_REQUEST;
            case 401:
                return ErrorKind.UNAUTHORIZED;
            case 403:
                return ErrorKind.FORBIDDEN;
            case 429:
                return retryAfter == null ? ErrorKind.QUOTA_EXCEEDED : ErrorKind.RATE_LIMITED;
            default:
                // Any other 4xx is a CLIENT error. Falling through to SERVER_ERROR would make it
                // retryable, so a misspelled database id would be retried twice before failing.
                // Classified on the RANGE rather than an enumerated list, which is the mistake the
                // shared corpus exists to catch. Only 5xx and transport failures are worth a retry.
                return status < 500 ? ErrorKind.BAD_REQUEST : ErrorKind.SERVER_ERROR;
        }
    }

    // Every v2 refusal is `{"rc": "SOMETHING"}`, and that code is the whole message: it says which
    // 403 this is, which the status cannot.
    private static String messageOf(ApiException e) {
        String body = e.getResponseBody();
        if (body != null && !body.isBlank()) {
            try {
                JsonNode rc = MAPPER.readTree(body).get("rc");
                if (rc != null && rc.isTextual()) {
                    return rc.asText();
                }
            } catch (Exception ignored) {
                // A non-JSON body is not worth failing over; fall through to the generic message.
            }
        }
        if (e.getCode() != 0) {
            return "request failed with status " + e.getCode();
        }
        Throwable cause = e.getCause();
        return cause != null && cause.getMessage() != null ? cause.getMessage() : "request failed";
    }

    private static Duration retryAfterOf(ApiException e) {
        if (e.getResponseHeaders() == null) {
            return null;
        }
        String value = e.getResponseHeaders().firstValue("Retry-After").orElse(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException notSeconds) {
            // The header also permits an HTTP date.
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
            Duration wait = Duration.between(Instant.now(), when.toInstant());
            return wait.isNegative() ? Duration.ZERO : wait;
        } catch (DateTimeParseException notADate) {
            return null;
        }
    }

    private static Duration backoff(int attempt) {
        Duration wait = BACKOFF_BASE.multipliedBy(1L << Math.min(attempt, 16));
        return wait.compareTo(BACKOFF_CAP) > 0 ? BACKOFF_CAP : wait;
    }

    private static void sleep(Duration wait) {
        if (wait.isZero() || wait.isNegative()) {
            return;
        }
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InternetDataException(ErrorKind.NETWORK, "interrupted while waiting to retry", e);
        }
    }
}
