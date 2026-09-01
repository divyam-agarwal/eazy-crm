package com.easycrm.platform.error;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    // constructor) and EI_EXPOSE_REP (the accessor). Map.copyOf breaks both: the record
    // never retains the caller's reference, and the accessor hands back something nobody
    // can mutate afterward. Guarded explicitly for null, since Map.copyOf(null) throws and
    // null is exactly today's "no fields" case, which must keep serializing without the key.
    public ApiError {
        fields = fields == null ? null : Map.copyOf(fields);
    }
}
