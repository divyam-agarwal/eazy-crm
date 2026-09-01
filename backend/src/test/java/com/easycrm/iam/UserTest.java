package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.error.ConflictException;
import org.junit.jupiter.api.Test;

class UserTest {

    private User active(Role role) {
        return new User("a@b.test", null, "hash", role, UserStatus.ACTIVE);
    }

    @Test
    void changeRoleReplacesTheRole() {
        User u = active(Role.SALES_EXEC);
        u.changeRole(Role.OWNER);
        assertEquals(Role.OWNER, u.getRole());
    }

    @Test
    void changingToTheSameRoleIsIdempotentRatherThanAConflict() {
        User u = active(Role.OWNER);
        assertDoesNotThrow(() -> u.changeRole(Role.OWNER));
        assertEquals(Role.OWNER, u.getRole());
    }

    @Test
    void disableFlipsStatus() {
        User u = active(Role.SALES_EXEC);
        u.disable();
        assertEquals(UserStatus.DISABLED, u.getStatus());
    }

    @Test
    void disablingATwiceDisabledMemberConflicts() {
        User u = active(Role.SALES_EXEC);
        u.disable();
        ConflictException ex = assertThrows(ConflictException.class, u::disable);
        assertEquals("member is already disabled", ex.getMessage());
    }

    @Test
    void enableFlipsStatusBack() {
        User u = active(Role.SALES_EXEC);
        u.disable();
        u.enable();
        assertEquals(UserStatus.ACTIVE, u.getStatus());
    }

    @Test
    void enablingAnActiveMemberConflicts() {
        User u = active(Role.SALES_EXEC);
        ConflictException ex = assertThrows(ConflictException.class, u::enable);
        assertEquals("member is already active", ex.getMessage());
    }

    @Test
    void aRejectedTransitionLeavesStatusUntouched() {
        User u = active(Role.SALES_EXEC);
        assertThrows(ConflictException.class, u::enable);
        assertEquals(UserStatus.ACTIVE, u.getStatus(), "the guard must run before any assignment");
    }
}
