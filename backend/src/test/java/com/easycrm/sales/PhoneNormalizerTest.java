package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneNormalizerTest {

    @Test
    void stripsFormattingAndCountryCodeToTenDigits() {
        assertThat(PhoneNormalizer.normalize("+91 98765 43210")).isEqualTo("9876543210");
        assertThat(PhoneNormalizer.normalize("098765 43210")).isEqualTo("9876543210");
        assertThat(PhoneNormalizer.normalize("(98765)-43210")).isEqualTo("9876543210");
        assertThat(PhoneNormalizer.normalize("9876543210")).isEqualTo("9876543210");
        assertThat(PhoneNormalizer.normalize("91-98765-43210")).isEqualTo("9876543210");
    }

    @Test
    void rejectsWrongLength() {
        assertThatThrownBy(() -> PhoneNormalizer.normalize("98765"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PhoneNormalizer.normalize("12345678901")) // 11 digits, no leading 0
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PhoneNormalizer.normalize(null))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PhoneNormalizer.normalize("   "))
            .isInstanceOf(ValidationException.class);
    }
}
