package com.easycrm.crm;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ContactRepository extends JpaRepository<Contact, UUID> {

    @Transactional(readOnly = true)
    List<Contact> findByCustomerId(UUID customerId);

    /**
     * Named "Primary", not "IsPrimary": Contact's boolean field is {@code primary} (getter {@code
     * isPrimary()}), and Spring Data's derived-query parser resolves against the entity's property
     * name, not the JavaBean getter spelling -- {@code findByCustomerIdAndIsPrimaryTrue} fails at
     * startup with PropertyReferenceException: No property 'isPrimary' found for type 'Contact'.
     *
     * <p>@Transactional(readOnly = true) is load-bearing: Spring Data does not wrap derived finders, so
     * without it the RLS tenant GUC is unset and this returns zero rows (challenge #8) — which would
     * make demotion silently do nothing.
     */
    @Transactional(readOnly = true)
    List<Contact> findByCustomerIdAndPrimaryTrue(UUID customerId);
}
