package com.easycrm.sales;

import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentNumberService {

    private final DocumentCounterRepository counters;

    public DocumentNumberService(DocumentCounterRepository counters) {
        this.counters = counters;
    }

    /**
     * Assigns the next gapless quote number for the tenant/FY of {@code onDate}. Must run in
     * the caller's transaction (default REQUIRED propagation) so the FOR UPDATE lock and the
     * increment commit or roll back atomically with the send. A rolled-back send releases the
     * lock without consuming a number.
     */
    @Transactional
    public String nextQuoteNo(LocalDate onDate) {
        String fy = financialYear(onDate);
        DocumentCounter counter =
                counters.findForUpdate("QUOTE", fy).orElseGet(() -> counters.save(new DocumentCounter("QUOTE", fy)));
        long value = counter.getNextVal();
        counter.increment();
        return String.format("QT/%s/%04d", fy, value);
    }

    /**
     * Assigns the next gapless order number for the tenant/FY of {@code onDate}. Uses a
     * "ORDER" counter, independent of "QUOTE". Must run in the caller's transaction so the
     * FOR UPDATE lock and increment commit atomically with the accept.
     */
    @Transactional
    public String nextOrderNo(LocalDate onDate) {
        String fy = financialYear(onDate);
        DocumentCounter counter =
                counters.findForUpdate("ORDER", fy).orElseGet(() -> counters.save(new DocumentCounter("ORDER", fy)));
        long value = counter.getNextVal();
        counter.increment();
        return String.format("ORD/%s/%04d", fy, value);
    }

    /** Indian financial year label (Apr 1 – Mar 31), e.g. 2025-06-01 → "25-26". */
    public static String financialYear(LocalDate d) {
        int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return String.format("%02d-%02d", start % 100, (start + 1) % 100);
    }
}
