package com.easycrm.crm;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "contact")
public class Contact extends TenantScopedEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private String name;

    @Column(length = 20)
    private String phone;

    @Column(name = "whatsapp_number", length = 20)
    private String whatsappNumber;

    @Column
    private String email;

    @Column(length = 128)
    private String designation;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    protected Contact() {}

    public Contact(
            UUID customerId,
            String name,
            String phone,
            String whatsappNumber,
            String email,
            String designation,
            boolean primary) {
        this.customerId = customerId;
        this.name = name;
        this.phone = phone;
        this.whatsappNumber = whatsappNumber;
        this.email = email;
        this.designation = designation;
        this.primary = primary;
    }

    public void update(
            String name, String phone, String whatsappNumber, String email, String designation, boolean primary) {
        this.name = name;
        this.phone = phone;
        this.whatsappNumber = whatsappNumber;
        this.email = email;
        this.designation = designation;
        this.primary = primary;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public String getWhatsappNumber() {
        return whatsappNumber;
    }

    public String getEmail() {
        return email;
    }

    public String getDesignation() {
        return designation;
    }

    public boolean isPrimary() {
        return primary;
    }
}
