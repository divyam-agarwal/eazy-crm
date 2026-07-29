package com.easycrm.tenant.web.dto;

import com.easycrm.tenant.Tenant;

public record TenantResponse(String id, String businessName, String gstin, String stateCode,
                             String address, String phone, String email) {

    public static TenantResponse of(Tenant t) {
        return new TenantResponse(t.getId().toString(), t.getBusinessName(), t.getGstin(),
            t.getStateCode(), t.getAddress(), t.getPhone(), t.getEmail());
    }
}
