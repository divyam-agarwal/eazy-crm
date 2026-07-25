package com.easycrm.catalog.web.dto;

import com.easycrm.catalog.Product;
import com.easycrm.catalog.Uom;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponse(UUID id, String sku, String name, String hsnCode,
                              Uom uom, BigDecimal gstRate, BigDecimal baseRate, boolean active) {

    public static ProductResponse of(Product p) {
        return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getHsnCode(),
                                   p.getUom(), p.getGstRate(), p.getBaseRate(), p.isActive());
    }
}
