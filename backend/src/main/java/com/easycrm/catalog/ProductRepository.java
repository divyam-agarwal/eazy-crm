package com.easycrm.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.transaction.annotation.Transactional;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    // @Transactional(readOnly = true): derived finders are not tx-wrapped by Spring Data,
    // so without this the RLS tenant GUC is unset and the query returns zero rows (challenge #8).
    @Transactional(readOnly = true)
    Optional<Product> findBySku(String sku);
}
