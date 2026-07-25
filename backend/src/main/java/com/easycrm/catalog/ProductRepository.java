package com.easycrm.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    // @Transactional(readOnly = true): derived finders are not tx-wrapped by Spring Data,
    // so without this the RLS tenant GUC is unset and the query returns zero rows (challenge #8).
    @Transactional(readOnly = true)
    Optional<Product> findBySku(String sku);

    @Transactional(readOnly = true)
    Page<Product> findByActive(boolean active, Pageable pageable);
}
