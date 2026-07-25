package com.easycrm.platform.gst;

import com.easycrm.platform.error.ValidationException;

import java.util.HashSet;
import java.util.Set;

/** Valid GST state codes: 01–38, plus 97 (Other Territory) and 99 (Centre Jurisdiction). */
public final class StateCode {

    private static final Set<String> VALID;
    static {
        Set<String> v = new HashSet<>();
        for (int i = 1; i <= 38; i++) v.add(String.format("%02d", i));
        v.add("97");
        v.add("99");
        VALID = Set.copyOf(v);
    }

    private StateCode() {}

    public static boolean isValid(String code) {
        return code != null && VALID.contains(code);
    }

    public static void requireValid(String code) {
        if (!isValid(code)) throw new ValidationException("stateCode", "invalid GST state code");
    }
}
