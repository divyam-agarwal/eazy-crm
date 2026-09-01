package com.easycrm.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface PriceListItemRepository extends JpaRepository<PriceListItem, UUID> {

    @Transactional(readOnly = true)
    List<PriceListItem> findByPriceListId(UUID priceListId);

    @Transactional(readOnly = true)
    Optional<PriceListItem> findByPriceListIdAndProductId(UUID priceListId, UUID productId);
}
