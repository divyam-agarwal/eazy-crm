package com.easycrm.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface EnquiryRepository
        extends JpaRepository<Enquiry, UUID>, JpaSpecificationExecutor<Enquiry> {

    // RLS-scoped derived query: must run inside a transaction so the tenant GUC is set,
    // otherwise RLS returns zero rows (see engineering-challenges #8).
    @Transactional(readOnly = true)
    List<Enquiry> findByNormalizedPhone(String normalizedPhone);
}
