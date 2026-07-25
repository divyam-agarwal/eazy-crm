package com.easycrm.crm;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    @Transactional(readOnly = true)
    Optional<Customer> findByGstin(String gstin);

    @Transactional(readOnly = true)
    Page<Customer> findByActive(boolean active, Pageable pageable);
}
