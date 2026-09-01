package com.easycrm.sales;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface QuotationVersionRepository extends JpaRepository<QuotationVersion, UUID> {

    @Transactional(readOnly = true)
    List<QuotationVersion> findByQuotationIdOrderByVersionNoAsc(UUID quotationId);

    @Transactional(readOnly = true)
    Optional<QuotationVersion> findByQuotationIdAndVersionNo(UUID quotationId, int versionNo);
}
