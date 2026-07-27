package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "enquiry")
public class Enquiry extends TenantScopedEntity {

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "contact_name", nullable = false, length = 200)
    private String contactName;

    @Column(name = "contact_phone", nullable = false, length = 20)
    private String contactPhone;

    @Column(name = "normalized_phone", nullable = false, length = 10)
    private String normalizedPhone;

    @Column(name = "contact_email", length = 254)
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EnquirySource source;

    @Column(name = "requirement_text", length = 2000)
    private String requirementText;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EnquiryStage stage;

    @Column(name = "expected_value", precision = 18, scale = 2)
    private BigDecimal expectedValue;

    @Column(name = "lost_reason", length = 500)
    private String lostReason;

    protected Enquiry() {}

    public Enquiry(UUID customerId, String contactName, String contactPhone, String normalizedPhone,
                   String contactEmail, EnquirySource source, String requirementText,
                   UUID assignedTo, BigDecimal expectedValue) {
        this.customerId = customerId;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.normalizedPhone = normalizedPhone;
        this.contactEmail = contactEmail;
        this.source = source;
        this.requirementText = requirementText;
        this.assignedTo = assignedTo;
        this.expectedValue = expectedValue;
        this.stage = EnquiryStage.NEW;
    }

    /** Edit header fields. Allowed only while the enquiry is active. */
    public void updateHeader(UUID customerId, String contactName, String contactPhone,
                             String normalizedPhone, String contactEmail, EnquirySource source,
                             String requirementText, UUID assignedTo, BigDecimal expectedValue) {
        requireActive("edited");
        this.customerId = customerId;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.normalizedPhone = normalizedPhone;
        this.contactEmail = contactEmail;
        this.source = source;
        this.requirementText = requirementText;
        this.assignedTo = assignedTo;
        this.expectedValue = expectedValue;
    }

    /** Advance to a later active stage (NEW < CONTACTED < QUALIFIED). Skips allowed; no going back. */
    public void advanceTo(EnquiryStage target) {
        requireActive("advanced");
        if (!target.isActive() || target.ordinal() <= this.stage.ordinal()) {
            throw new ValidationException("stage",
                "can only advance to a later active stage");
        }
        this.stage = target;
    }

    public void lose(String lostReason) {
        requireActive("lost");
        if (lostReason == null || lostReason.isBlank()) {
            throw new ValidationException("lostReason", "a reason is required to mark an enquiry lost");
        }
        this.stage = EnquiryStage.LOST;
        this.lostReason = lostReason;
    }

    /** Reserved for the later enquiry->quotation conversion slice; no controller reaches this yet. */
    public void markConverted() {
        requireActive("converted");
        this.stage = EnquiryStage.CONVERTED;
    }

    private void requireActive(String verb) {
        if (stage.isTerminal()) {
            throw new ValidationException("stage",
                "a " + stage.name().toLowerCase() + " enquiry cannot be " + verb);
        }
    }

    public UUID getCustomerId() { return customerId; }
    public String getContactName() { return contactName; }
    public String getContactPhone() { return contactPhone; }
    public String getNormalizedPhone() { return normalizedPhone; }
    public String getContactEmail() { return contactEmail; }
    public EnquirySource getSource() { return source; }
    public String getRequirementText() { return requirementText; }
    public UUID getAssignedTo() { return assignedTo; }
    public EnquiryStage getStage() { return stage; }
    public BigDecimal getExpectedValue() { return expectedValue; }
    public String getLostReason() { return lostReason; }
}
