package com.easycrm.crm.web.dto;

import com.easycrm.crm.Contact;

import java.util.UUID;

public record ContactResponse(UUID id, UUID customerId, String name, String phone,
                              String whatsappNumber, String email, String designation,
                              boolean isPrimary) {

    public static ContactResponse of(Contact c) {
        return new ContactResponse(c.getId(), c.getCustomerId(), c.getName(), c.getPhone(),
            c.getWhatsappNumber(), c.getEmail(), c.getDesignation(), c.isPrimary());
    }
}
