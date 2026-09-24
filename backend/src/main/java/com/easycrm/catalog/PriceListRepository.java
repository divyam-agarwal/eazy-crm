package com.easycrm.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.transaction.annotation.Transactional;

public interface PriceListRepository extends JpaRepository<PriceList, UUID>, JpaSpecificationExecutor<PriceList> {

    @Transactional(readOnly = true)
    Optional<PriceList> findByName(String name);
}
