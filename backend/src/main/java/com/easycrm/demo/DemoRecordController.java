package com.easycrm.demo;

import com.easycrm.platform.error.NotFoundException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/demo-records")
public class DemoRecordController {

    private final DemoRecordRepository records;

    public DemoRecordController(DemoRecordRepository records) { this.records = records; }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        // findById is tenant-filtered by @TenantId AND row-secured by RLS.
        // A record owned by another tenant is simply not found -> 404, not 403.
        DemoRecord r = records.findById(id)
            .orElseThrow(() -> new NotFoundException("demo record not found"));
        return Map.of("id", r.getId(), "label", r.getLabel());
    }
}
