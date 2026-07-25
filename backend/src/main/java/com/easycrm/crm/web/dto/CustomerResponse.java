package com.easycrm.crm.web.dto;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerSource;

import java.util.UUID;

public record CustomerResponse(UUID id, String businessName, String gstin, String stateCode,
                               String billingAddress, String shippingAddress, int creditDays,
                               UUID assignedTo, UUID priceListId, CustomerSource source,
                               boolean active) {

    public static CustomerResponse of(Customer c) {
        return new CustomerResponse(c.getId(), c.getBusinessName(), c.getGstin(), c.getStateCode(),
            c.getBillingAddress(), c.getShippingAddress(), c.getCreditDays(),
            c.getAssignedTo(), c.getPriceListId(), c.getSource(), c.isActive());
    }
}
