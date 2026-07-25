package com.easycrm.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface PriceListRepository extends JpaRepository<PriceList, UUID> {

    @Transactional(readOnly = true)
    Optional<PriceList> findByName(String name);

    @Transactional(readOnly = true)
    Page<PriceList> findByActive(boolean active, Pageable pageable);
}
