package com.easycrm.sales;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface QuotationRepository extends JpaRepository<Quotation, UUID> {

    @Transactional(readOnly = true)
    Page<Quotation> findByStatus(QuotationStatus status, Pageable pageable);

    @Transactional(readOnly = true)
    Page<Quotation> findByCustomerId(UUID customerId, Pageable pageable);
}
