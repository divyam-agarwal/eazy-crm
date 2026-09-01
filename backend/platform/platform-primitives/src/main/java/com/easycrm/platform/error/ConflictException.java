package com.easycrm.platform.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The request conflicts with current state. Mapped to HTTP 409 by ApiExceptionHandler.
 *
 * <p>{@code fields} is optional and null for almost every conflict in this codebase — the
 * message alone is the contract. It exists for a conflict a client must act on
 * programmatically rather than merely display: the members-management reassign-first gate
 * returns the count of open work per aggregate so a frontend can route the owner to the
 * right screen instead of parsing prose. See spec 2026-09-01-members-management-design.md §4.4.
 *
 * <p>LinkedHashMap, not Map.copyOf: an immutable map's iteration order is salt-randomized
 * per JVM boot, so the same multi-key conflict would serialize its keys in a different order
 * from one deploy to the next. The copy is also what stops SpotBugs flagging EI_EXPOSE_REP2.
 */
public class ConflictException extends RuntimeException {

    private final Map<String, Object> fields;

    public ConflictException(String message) {
        this(message, null);
    }

    public ConflictException(String message, Map<String, Object> fields) {
        super(message);
        this.fields = fields == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** Null when this conflict carries no structured detail — the common case. */
    public Map<String, Object> getFields() {
        return fields;
    }
}
