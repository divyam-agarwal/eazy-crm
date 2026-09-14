package com.easycrm.platform.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Field-level domain validation failure. Mapped to HTTP 422 by ApiExceptionHandler.
 *
 * <p>{@code codes} maps a field to a stable, machine-readable reason (SCREAMING_SNAKE, see
 * docs/api/error-codes.md) so a client can translate the error instead of displaying English
 * prose. Optional: an exception built without codes has an empty map, and the client falls back to
 * the message. LinkedHashMap for a deterministic serialized key order (see ApiError).
 */
public class ValidationException extends RuntimeException {

    private final Map<String, String> fields;
    private final Map<String, String> codes;

    public ValidationException(Map<String, String> fields) {
        this(fields, Map.of());
    }

    public ValidationException(Map<String, String> fields, Map<String, String> codes) {
        super("validation failed");
        // Defensive copy: without it SpotBugs flags EI_EXPOSE_REP2 here and EI_EXPOSE_REP on
        // getFields() for storing/returning the caller's mutable map verbatim. Same discipline
        // as ConflictException.fields and ApiError.fields.
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.codes = Collections.unmodifiableMap(new LinkedHashMap<>(codes));
    }

    public ValidationException(String field, String message) {
        this(Map.of(field, message));
    }

    public ValidationException(String field, String message, String code) {
        this(Map.of(field, message), Map.of(field, code));
    }

    public Map<String, String> getFields() {
        return fields;
    }

    /** Never null; empty when the thrower supplied no codes. */
    public Map<String, String> getCodes() {
        return codes;
    }
}
