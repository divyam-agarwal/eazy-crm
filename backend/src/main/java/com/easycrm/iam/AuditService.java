package com.easycrm.iam;

import org.springframework.stereotype.Service;
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
}
