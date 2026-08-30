package com.easycrm.sales.web;

import com.easycrm.platform.visibility.SubjectType;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.sales.ActivityService;
import com.easycrm.sales.web.dto.ActivityCreateRequest;
import com.easycrm.sales.web.dto.ActivityResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/activities")
public class ActivityController {

    private final ActivityService service;

    public ActivityController(ActivityService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ActivityResponse> create(@Valid @RequestBody ActivityCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    /**
     * subjectType and subjectId are REQUIRED, and that is the point: there is no unscoped
     * activity list, because an activity's visibility is derived from its subject and the
     * only gate is resolving that subject. Omitting either yields 400 from Spring before
     * any code runs. See spec 2026-08-30-activity-follow-up-design.md §4.2, §9.
     */
    @GetMapping
    public PageResponse<ActivityResponse> list(@RequestParam SubjectType subjectType,
                                               @RequestParam UUID subjectId,
                                               Pageable pageable) {
        return service.list(subjectType, subjectId, pageable);
    }
}
