package com.easycrm.platform.error;

/**
 * The envelope every error response in this API is wrapped in: {@code {"error": {...}}}.
 * Exists as a named type so the generated OpenAPI document can reference one schema for every
 * 4xx instead of the property-less {@code object} a raw {@code Map} produced.
 */
public record ApiErrorResponse(ApiError error) {}
