package com.easycrm.platform.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The inner half of this API's single error envelope.
 *
 * <p>{@code fields} stays a free-form map on purpose: its keys are dynamic field names taken
 * from a binding result or from {@link ValidationException#getFields()}, so there is no closed
 * schema to write, and the generated OpenAPI document describes it as a free-form object —
 * honest rather than lazy.
 *
 * <p>{@code @JsonInclude} sits on the type, not on the {@code fields} component: {@code code}
 * and {@code message} are never null, so type-level NON_NULL is equivalent, is one annotation
 * instead of one per component, and does not rest on record-component annotation propagation
 * behaving as assumed. Without it, an error with no field detail would serialize
 * {@code "fields":null} — a different document from today's, which omits the key entirely.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, Map<String, Object> fields) {

    // Defensive copy: a record component of type Map stores and returns whatever mutable
    // map the caller passes in, verbatim -- SpotBugs flags that as EI_EXPOSE_REP2 (the
    // constructor) and EI_EXPOSE_REP (the accessor). Collections.unmodifiableMap(new
    // LinkedHashMap<>(fields)) breaks both: the record never retains the caller's
    // reference, and the accessor hands back something nobody can mutate afterward.
    //
    // LinkedHashMap specifically, not Map.copyOf/HashMap: Map.copyOf returns
    // ImmutableCollections.MapN, whose iteration order is salt-randomized per JVM boot --
    // fine for a single-entry map (this test suite's only multi-key producer,
    // ProductService.validate, emits up to three) but it means the *same* multi-field
    // error would serialize its fields in a different key order from one deploy to the
    // next. LinkedHashMap preserves the caller's HashMap's iteration order at copy time,
    // so the emitted bytes stay identical to what the pre-conversion HashMap-based
    // handler produced, not just semantically equivalent to it -- which is what "the
    // wire format does not change" actually requires here.
    //
    // Null-tolerant for the same reason as before: Map.copyOf(null) would have thrown,
    // and null is exactly today's "no fields" case, which must keep serializing without
    // the key. As a side effect, LinkedHashMap's copy constructor (unlike Map.copyOf)
    // also tolerates a null *value* inside the map -- MethodArgumentNotValidException's
    // handler builds fields from FieldError.getDefaultMessage(), which is null only for a
    // bean-validation constraint declared without a message (not the case for any
    // constraint in this codebase today, but a latent 500 that Map.copyOf would have
    // introduced for that path is now moot).
    public ApiError {
        fields = fields == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
