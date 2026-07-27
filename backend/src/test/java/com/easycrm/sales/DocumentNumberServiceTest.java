package com.easycrm.sales;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentNumberServiceTest extends IntegrationTest {
    @Autowired DocumentNumberService service;
    @Autowired PlatformTransactionManager txManager;

    private static final LocalDate FY_25_26 = LocalDate.of(2025, 6, 1);

    @AfterEach void clear() { TenantContext.clear(); }

    private void asTenant(UUID t) {
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
    }

    private int suffix(String quoteNo) { // "QT/25-26/0007" -> 7
        return Integer.parseInt(quoteNo.substring(quoteNo.lastIndexOf('/') + 1));
    }

    @Test
    void financialYearLabelsAprToMar() {
        assertThat(DocumentNumberService.financialYear(LocalDate.of(2025, 6, 1))).isEqualTo("25-26");
        assertThat(DocumentNumberService.financialYear(LocalDate.of(2026, 3, 31))).isEqualTo("25-26");
        assertThat(DocumentNumberService.financialYear(LocalDate.of(2026, 4, 1))).isEqualTo("26-27");
    }

    @Test
    void numbersAreSequentialWithinTenantAndFy() {
        asTenant(UUID.randomUUID());
        assertThat(service.nextQuoteNo(FY_25_26)).isEqualTo("QT/25-26/0001");
        assertThat(service.nextQuoteNo(FY_25_26)).isEqualTo("QT/25-26/0002");
        assertThat(service.nextQuoteNo(LocalDate.of(2026, 4, 1))).isEqualTo("QT/26-27/0001"); // FY rollover
    }

    @Test
    void rolledBackSendConsumesNoNumber() {
        UUID tenant = UUID.randomUUID();
        asTenant(tenant);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        String inRolledBack = tx.execute(status -> {
            String n = service.nextQuoteNo(FY_25_26);
            status.setRollbackOnly();
            return n;
        });
        assertThat(suffix(inRolledBack)).isEqualTo(1);
        // The rolled-back number is NOT burned — the next successful call reuses it. No gap.
        assertThat(suffix(service.nextQuoteNo(FY_25_26))).isEqualTo(1);
    }

    @Test
    void orderNumbersAreGaplessAndIndependentOfQuoteNumbers() {
        UUID tenant = UUID.randomUUID();
        asTenant(tenant);
        LocalDate d = LocalDate.of(2026, 7, 27); // FY 26-27
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // A quote counter in the same tenant/FY must not affect order numbering.
        tx.executeWithoutResult(s -> service.nextQuoteNo(d));

        String o1 = tx.execute(s -> service.nextOrderNo(d));
        String o2 = tx.execute(s -> service.nextOrderNo(d));
        assertThat(o1).isEqualTo("ORD/26-27/0001");
        assertThat(o2).isEqualTo("ORD/26-27/0002");
    }

    @Test
    void concurrentSendsGetDistinctConsecutiveNumbers() throws Exception {
        UUID tenant = UUID.randomUUID();
        asTenant(tenant);
        service.nextQuoteNo(FY_25_26); // seed the counter row (now at 0002) so the race is on the lock

        Callable<Integer> send = () -> {
            asTenant(tenant); // ThreadLocal per worker thread
            try {
                return suffix(service.nextQuoteNo(FY_25_26));
            } finally {
                TenantContext.clear();
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> f1 = pool.submit(send);
            Future<Integer> f2 = pool.submit(send);
            Set<Integer> got = Set.of(f1.get(), f2.get());
            assertThat(got).containsExactlyInAnyOrderElementsOf(List.of(2, 3)); // distinct, gapless
        } finally {
            pool.shutdownNow();
        }
    }
}
