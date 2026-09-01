package com.easycrm.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageResponseTest {

    @Test
    void mapsSpringPageMetadata() {
        PageResponse<String> r = PageResponse.of(new PageImpl<>(List.of("a", "b"), PageRequest.of(0, 20), 2));
        assertEquals(List.of("a", "b"), r.content());
        assertEquals(0, r.page());
        assertEquals(20, r.size());
        assertEquals(2, r.totalElements());
        assertEquals(1, r.totalPages());
    }
}
