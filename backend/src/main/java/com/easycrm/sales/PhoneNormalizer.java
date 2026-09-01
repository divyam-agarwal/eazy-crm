package com.easycrm.sales;

import com.easycrm.platform.error.ValidationException;

/**
 * Canonicalises an Indian phone number to its 10-digit national form so it can serve
 * as a stable dedupe key. Strips formatting, a +91 country code, and a leading trunk 0.
 */
public final class PhoneNormalizer {

    private PhoneNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("contactPhone", "phone number is required");
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        if (digits.length() != 10) {
            throw new ValidationException("contactPhone", "must be a valid 10-digit Indian phone number");
        }
        return digits;
    }
}
