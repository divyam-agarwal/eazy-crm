package com.easycrm.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easycrm.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

class EnquiryTest {

    private Enquiry newEnquiry() {
        return new Enquiry(
                null, "Ravi", "9876543210", "9876543210", null, EnquirySource.INDIAMART, "10 bags cement", null, null);
    }

    @Test
    void startsInNewStage() {
        assertThat(newEnquiry().getStage()).isEqualTo(EnquiryStage.NEW);
    }

    @Test
    void advancesForwardIncludingSkip() {
        Enquiry e = newEnquiry();
        e.advanceTo(EnquiryStage.QUALIFIED); // skip CONTACTED — allowed
        assertThat(e.getStage()).isEqualTo(EnquiryStage.QUALIFIED);
    }

    @Test
    void rejectsBackwardAndSameStage() {
        Enquiry e = newEnquiry();
        e.advanceTo(EnquiryStage.CONTACTED);
        assertThatThrownBy(() -> e.advanceTo(EnquiryStage.NEW)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> e.advanceTo(EnquiryStage.CONTACTED)).isInstanceOf(ValidationException.class);
    }

    @Test
    void advanceCannotTargetTerminalStages() {
        assertThatThrownBy(() -> newEnquiry().advanceTo(EnquiryStage.LOST)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> newEnquiry().advanceTo(EnquiryStage.CONVERTED))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void loseRequiresReasonAndIsTerminal() {
        Enquiry e = newEnquiry();
        assertThatThrownBy(() -> e.lose("  ")).isInstanceOf(ValidationException.class);
        e.lose("bought elsewhere");
        assertThat(e.getStage()).isEqualTo(EnquiryStage.LOST);
        assertThat(e.getLostReason()).isEqualTo("bought elsewhere");
        // terminal: no further transitions
        assertThatThrownBy(() -> e.advanceTo(EnquiryStage.QUALIFIED)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> e.lose("again")).isInstanceOf(ValidationException.class);
    }

    @Test
    void markConvertedIsTerminalAndActiveOnly() {
        Enquiry e = newEnquiry();
        e.markConverted();
        assertThat(e.getStage()).isEqualTo(EnquiryStage.CONVERTED);
        assertThatThrownBy(() -> e.markConverted()).isInstanceOf(ValidationException.class);
    }

    @Test
    void updateHeaderRejectedOnTerminal() {
        Enquiry e = newEnquiry();
        e.lose("gone");
        assertThatThrownBy(() -> e.updateHeader(
                        null, "X", "9999999999", "9999999999", null, EnquirySource.PHONE, null, null, null))
                .isInstanceOf(ValidationException.class);
    }
}
