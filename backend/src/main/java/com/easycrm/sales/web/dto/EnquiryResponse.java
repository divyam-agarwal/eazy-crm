package com.easycrm.sales.web.dto;

import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquirySource;
import com.easycrm.sales.EnquiryStage;

import java.math.BigDecimal;
import java.util.UUID;

public record EnquiryResponse(
    UUID id, UUID customerId, String contactName, String contactPhone, String normalizedPhone,
    String contactEmail, EnquirySource source, String requirementText, UUID assignedTo,
    EnquiryStage stage, BigDecimal expectedValue, String lostReason) {

    public static EnquiryResponse of(Enquiry e) {
        return new EnquiryResponse(e.getId(), e.getCustomerId(), e.getContactName(),
            e.getContactPhone(), e.getNormalizedPhone(), e.getContactEmail(), e.getSource(),
            e.getRequirementText(), e.getAssignedTo(), e.getStage(), e.getExpectedValue(),
            e.getLostReason());
    }
}
