package com.easycrm.iam;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository logs;

    public AuditService(AuditLogRepository logs) { this.logs = logs; }

    /** Must be called within a tenant context (RLS enforces tenant_id on insert). */
    @Transactional
    public void record(String action, UUID actorUserId, Map<String, Object> detail) {
        logs.save(new AuditLog(action, actorUserId, detail));
    }

    /**
     * Records an audit row in its OWN transaction (REQUIRES_NEW), so it survives even when
     * the caller's transaction rolls back. Used for events that must be captured on the
     * failure path — e.g. LOGIN_FAILED, where the caller throws a 401 and rolls back. The
     * tenant context must still be set so the new transaction's GUC passes RLS.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(String action, UUID actorUserId, Map<String, Object> detail) {
        logs.save(new AuditLog(action, actorUserId, detail));
    }
}
