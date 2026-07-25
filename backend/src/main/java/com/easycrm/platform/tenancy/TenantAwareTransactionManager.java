package com.easycrm.platform.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Sets a transaction-scoped Postgres GUC (app.current_tenant) so Row-Level Security
 * enforces tenant isolation at the database. Uses set_config(..., is_local => true)
 * because SET LOCAL cannot take a bind parameter; is_local=true clears it at
 * commit/rollback, so no value leaks back into the pooled connection.
 */
public class TenantAwareTransactionManager extends JpaTransactionManager {

    public TenantAwareTransactionManager(EntityManagerFactory emf) { super(emf); }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        UUID tenantId = TenantContext.tenantId();
        if (tenantId == null) return; // leave GUC unset -> scoped tables see zero rows

        EntityManagerHolder holder =
            (EntityManagerHolder) TransactionSynchronizationManager.getResource(getEntityManagerFactory());
        if (holder == null) return;
        EntityManager em = holder.getEntityManager();
        em.createNativeQuery("SELECT set_config('app.current_tenant', :tid, true)")
          .setParameter("tid", tenantId.toString())
          .getSingleResult();
    }
}
