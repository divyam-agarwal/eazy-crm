package com.easycrm.crm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, UUID> {

    @Transactional(readOnly = true)
    List<Contact> findByCustomerId(UUID customerId);
}
