package com.easycrm.iam;

import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "app_user", // "user" is a reserved word in PostgreSQL
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_user_tenant_email",
                        columnNames = {"tenant_id", "email"}))
public class User extends TenantScopedEntity {

    @Column(nullable = false)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserStatus status;

    protected User() {}

    public User(String email, String phone, String passwordHash, Role role, UserStatus status) {
        this.email = email;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    /**
     * No guard, deliberately: assigning the role a member already holds is harmless, and
     * rejecting it would fail a retried request for no reason. The invariant that a
     * workspace keeps at least one active owner is tenant-wide and lives in MemberService —
     * an entity cannot count its siblings.
     */
    public void changeRole(Role newRole) {
        this.role = newRole;
    }

    public void disable() {
        if (status == UserStatus.DISABLED) {
            throw new ConflictException("member is already disabled");
        }
        this.status = UserStatus.DISABLED;
    }

    public void enable() {
        if (status == UserStatus.ACTIVE) {
            throw new ConflictException("member is already active");
        }
        this.status = UserStatus.ACTIVE;
    }
}
