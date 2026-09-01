package com.easycrm.sales;

import com.easycrm.iam.AssignedWorkload;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Non-terminal enquiries assigned to a member — a dropped lead if it stays with someone who
 * cannot log in.
 *
 * <p>An enquiry carries its OWN assigned_to and its customer_id is nullable (an enquiry
 * precedes the customer in this wedge), so reassigning a customer does NOT carry it. That
 * asymmetry is the reason this implementation exists.
 */
@Component
public class EnquiryWorkload implements AssignedWorkload {

    // Derived from isTerminal() rather than listed literally, so a new stage joins the right
    // side automatically instead of silently defaulting to "does not block a disable".
    private static final List<EnquiryStage> ACTIVE_STAGES =
            Arrays.stream(EnquiryStage.values()).filter(EnquiryStage::isActive).toList();

    private final EnquiryRepository enquiries;

    public EnquiryWorkload(EnquiryRepository enquiries) {
        this.enquiries = enquiries;
    }

    @Override
    public String label() {
        return "enquiries";
    }

    @Override
    public long countOpenFor(UUID userId) {
        return enquiries.countByAssignedToAndStageIn(userId, ACTIVE_STAGES);
    }
}
