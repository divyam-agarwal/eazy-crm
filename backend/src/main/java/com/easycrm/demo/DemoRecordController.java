package com.easycrm.demo;

import com.easycrm.platform.error.NotFoundException;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

// Excluded from the generated OpenAPI document on purpose. This controller is the P0
// tenant-isolation demonstration fixture -- it exists to prove @TenantId + RLS return 404
// rather than 403 for another tenant's row -- and it is not product surface a client should
// ever be written against. Hiding it does NOT remove the route: GET /api/v1/demo-records/{id}
// remains live and authenticated in every profile. If it should not exist in production, that
// is a separate decision and a separate slice.
@Hidden
@RestController
@RequestMapping("/api/v1/demo-records")
public class DemoRecordController {

    private final DemoRecordRepository records;

    public DemoRecordController(DemoRecordRepository records) {
        this.records = records;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        // findById is tenant-filtered by @TenantId AND row-secured by RLS.
        // A record owned by another tenant is simply not found -> 404, not 403.
        DemoRecord r = records.findById(id).orElseThrow(() -> new NotFoundException("demo record not found"));
        return Map.of("id", r.getId(), "label", r.getLabel());
    }
}
