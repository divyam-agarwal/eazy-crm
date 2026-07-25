package com.easycrm.platform.gst;

import com.easycrm.platform.error.ValidationException;

/**
 * A validated GSTIN (15 chars). The 15th char is a base-36 check digit computed from the
 * first 14 via a Luhn-mod-36 algorithm (GSTN spec). First two chars are the GST state code.
 */
public final class Gstin {

    private static final String CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CP = 36;

    private final String value;

    private Gstin(String value) { this.value = value; }

    public static Gstin parse(String raw) {
        if (raw == null) throw new ValidationException("gstin", "GSTIN is required");
        String g = raw.trim().toUpperCase();
        if (g.length() != 15) throw new ValidationException("gstin", "GSTIN must be 15 characters");
        for (int i = 0; i < 15; i++) {
            if (CHARSET.indexOf(g.charAt(i)) < 0)
                throw new ValidationException("gstin", "GSTIN has invalid characters");
        }
        if (checkChar(g.substring(0, 14)) != g.charAt(14))
            throw new ValidationException("gstin", "GSTIN checksum is invalid");
        return new Gstin(g);
    }

    private static char checkChar(String payload14) {
        int factor = 2, sum = 0;
        for (int i = payload14.length() - 1; i >= 0; i--) {
            int cp = CHARSET.indexOf(payload14.charAt(i));
            int d = factor * cp;
            d = (d / CP) + (d % CP);
            sum += d;
            factor = (factor == 2) ? 1 : 2;
        }
        return CHARSET.charAt((CP - (sum % CP)) % CP);
    }

    public String value() { return value; }
    public String stateCode() { return value.substring(0, 2); }
}
