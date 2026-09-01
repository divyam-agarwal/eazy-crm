package com.easycrm.sales;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface QuotationRepository extends JpaRepository<Quotation, UUID>, JpaSpecificationExecutor<Quotation> {}
