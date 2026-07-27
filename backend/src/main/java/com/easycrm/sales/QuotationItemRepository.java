package com.easycrm.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface QuotationItemRepository extends JpaRepository<QuotationItem, UUID> {

    @Transactional(readOnly = true)
    List<QuotationItem> findByVersionId(UUID versionId);

    void deleteByVersionId(UUID versionId);
}
