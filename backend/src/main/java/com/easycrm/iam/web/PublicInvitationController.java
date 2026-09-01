package com.easycrm.iam.web;

import com.easycrm.iam.InvitationService;
import com.easycrm.iam.web.dto.AcceptInvitationRequest;
import com.easycrm.iam.web.dto.AuthResponse;
import com.easycrm.iam.web.dto.InvitationPreviewResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pre-auth half of invitations. Split from InvitationController by authentication
 * posture, mirroring AuthController / PublicShareController — that split is what lets
 * SecurityConfig permit whole paths rather than individual methods.
 *
 * <p>Under /api/v1/auth/** on purpose: that prefix already carries a rate-limit policy, so
 * these inherit per-IP capping. An unmatched path is UNLIMITED
 * (RateLimitProperties.policyFor), which is what makes the prefix load-bearing rather than
 * cosmetic.
 */
@RestController
@RequestMapping("/api/v1/auth/invitations")
public class PublicInvitationController {

    private final InvitationService invitations;

    public PublicInvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping("/{token}/accept")
    public ResponseEntity<AuthResponse> accept(
            @PathVariable String token, @Valid @RequestBody AcceptInvitationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invitations.accept(token, req));
    }

    @GetMapping("/{token}")
    public InvitationPreviewResponse preview(@PathVariable String token) {
        return invitations.preview(token);
    }
}
