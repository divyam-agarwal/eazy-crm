package com.easycrm.sales;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    // Used by the idempotent accept path; the list endpoint filters via OrderSpecifications.
    Optional<Order> findByQuotationId(UUID quotationId);
}
