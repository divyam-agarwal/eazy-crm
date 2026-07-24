package com.easycrm.platform.tenancy;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class TransactionManagerConfig {

    @Bean
    @Primary
    PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new TenantAwareTransactionManager(emf);
    }
}
