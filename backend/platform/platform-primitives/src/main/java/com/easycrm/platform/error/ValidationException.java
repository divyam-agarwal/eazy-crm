package com.easycrm.platform.error;

import java.util.Map;

/** Field-level domain validation failure. Mapped to HTTP 422 by ApiExceptionHandler. */
public class ValidationException extends RuntimeException {

    private final Map<String, String> fields;

    public ValidationException(Map<String, String> fields) {
        super(buildMessage(fields));
        this.fields = fields;
    }

    public ValidationException(String field, String message) {
        this(Map.of(field, message));
    }

    public Map<String, String> getFields() { return fields; }

    private static String buildMessage(Map<String, String> fields) {
        return fields.isEmpty() ? "validation failed" : fields.values().iterator().next();
    }
}
