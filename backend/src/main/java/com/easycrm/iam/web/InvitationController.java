package com.easycrm.iam.web;

import com.easycrm.iam.InvitationService;
import com.easycrm.iam.web.dto.InvitationResponse;
import com.easycrm.iam.web.dto.InviteRequest;
import com.easycrm.iam.web.dto.PendingInvitationResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Authenticated, owner-only. The pre-auth half lives in PublicInvitationController. */
@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {

    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping
    public ResponseEntity<InvitationResponse> invite(@Valid @RequestBody InviteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invitations.invite(req));
    }

    @GetMapping
    public List<PendingInvitationResponse> listPending() {
        return invitations.listPending();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        invitations.revoke(id);
        return ResponseEntity.noContent().build();
    }
}
