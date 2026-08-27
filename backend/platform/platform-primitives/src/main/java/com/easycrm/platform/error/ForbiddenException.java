package com.easycrm.platform.error;

/**
 * The caller is authenticated and the record is inside their tenant, but their role
 * does not permit this operation. Distinct from a cross-tenant read, which is a 404
 * so that the response cannot confirm the record exists.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}
