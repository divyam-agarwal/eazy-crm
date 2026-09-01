package com.easycrm.iam;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log")
public class AuditLog extends TenantScopedEntity {

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> detail;

    protected AuditLog() {}

    public AuditLog(String action, UUID actorUserId, Map<String, Object> detail) {
        this.action = action;
        this.actorUserId = actorUserId;
        this.detail = detail;
    }

    public String getAction() {
        return action;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }
}
