package com.easycrm.iam.web;

import com.easycrm.iam.MemberService;
import com.easycrm.iam.web.dto.ChangeRoleRequest;
import com.easycrm.iam.web.dto.MemberResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated, owner-only. Every route needs a JWT, so unlike the invitations surface
 * there is no pre-auth half and no SecurityConfig change — /api/** is already authenticated.
 *
 * <p>Role change is POST /{id}/role rather than PATCH /{id}: PATCH house-wide is
 * full-header-replace, so a PATCH carrying only role would read as "clear the other fields
 * too". A verb sub-resource has no such ambiguity, and matches OrderController's transitions.
 */
@RestController
@RequestMapping("/api/v1/members")
public class MemberController {

    private final MemberService members;

    public MemberController(MemberService members) {
        this.members = members;
    }

    @GetMapping
    public List<MemberResponse> list() {
        return members.list();
    }

    @PostMapping("/{id}/role")
    public MemberResponse changeRole(@PathVariable UUID id, @Valid @RequestBody ChangeRoleRequest req) {
        return members.changeRole(id, req.role());
    }

    @PostMapping("/{id}/disable")
    public MemberResponse disable(@PathVariable UUID id) {
        return members.disable(id);
    }

    @PostMapping("/{id}/enable")
    public MemberResponse enable(@PathVariable UUID id) {
        return members.enable(id);
    }
}
