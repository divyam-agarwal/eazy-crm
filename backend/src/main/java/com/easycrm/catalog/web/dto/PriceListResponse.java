package com.easycrm.catalog.web.dto;

import com.easycrm.catalog.PriceList;

import java.util.UUID;

public record PriceListResponse(UUID id, String name, boolean active) {

    public static PriceListResponse of(PriceList p) {
        return new PriceListResponse(p.getId(), p.getName(), p.isActive());
    }
}
