package com.easycrm.sales.web;

import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.FollowUpScope;
import com.easycrm.sales.FollowUpService;
import com.easycrm.sales.FollowUpStatus;
import com.easycrm.sales.web.dto.FollowUpCancelRequest;
import com.easycrm.sales.web.dto.FollowUpCompleteRequest;
import com.easycrm.sales.web.dto.FollowUpCreateRequest;
import com.easycrm.sales.web.dto.FollowUpResponse;
import com.easycrm.sales.web.dto.FollowUpSummaryResponse;
import com.easycrm.sales.web.dto.FollowUpUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/follow-ups")
public class FollowUpController {

    private final FollowUpService service;

    public FollowUpController(FollowUpService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<FollowUpResponse> create(
            @Valid @RequestBody FollowUpCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public FollowUpResponse get(@PathVariable UUID id) { return service.get(id); }

    /** Unlike activities, this list needs no subject: a follow-up is filtered by owner. */
    @GetMapping
    public PageResponse<FollowUpResponse> list(
            @RequestParam(required = false) FollowUpScope scope,
            @RequestParam(required = false) FollowUpStatus status,
            @RequestParam(required = false) UUID assignedTo,
            @RequestParam(required = false) SubjectType subjectType,
            @RequestParam(required = false) UUID subjectId,
            Pageable pageable) {
        return service.list(scope, status, assignedTo, subjectType, subjectId, pageable);
    }

    @GetMapping("/summary")
    public FollowUpSummaryResponse summary() { return service.summary(); }

    /** Full-header-replace; an omitted nullable field is cleared. Pending only. */
    @PatchMapping("/{id}")
    public FollowUpResponse update(@PathVariable UUID id,
                                   @Valid @RequestBody FollowUpUpdateRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/complete")
    public FollowUpResponse complete(@PathVariable UUID id,
                                     @Valid @RequestBody FollowUpCompleteRequest req) {
        return service.complete(id, req);
    }

    @PostMapping("/{id}/cancel")
    public FollowUpResponse cancel(@PathVariable UUID id,
                                   @Valid @RequestBody FollowUpCancelRequest req) {
        return service.cancel(id, req.reason());
    }
}
