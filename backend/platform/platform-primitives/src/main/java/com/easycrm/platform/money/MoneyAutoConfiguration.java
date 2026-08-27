package com.easycrm.platform.money;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JacksonModule;

/**
 * Registers {@link BigDecimalStringModule} on the application ObjectMapper.
 *
 * <p>Auto-configuration rather than a component-scanned {@code @Configuration}: this module is a
 * jar, and a service whose {@code @SpringBootApplication} sits at {@code com.easycrm.sales} never
 * scans {@code com.easycrm.platform}. The bean would simply not exist, and the only symptom would
 * be money crossing the HTTP wire as a JSON number — no exception, no log line (MB1).
 *
 * <p>{@code @ConditionalOnClass(JacksonModule.class)} keeps the module usable with no Jackson and
 * no Spring at all, which is what lets notification-svc take this jar without a servlet stack.
 */
@AutoConfiguration
@ConditionalOnClass(JacksonModule.class)
public class MoneyAutoConfiguration {

    // Boot 4's JacksonAutoConfiguration injects a Collection<JacksonModule> into its mapper-builder
    // customizer, so any JacksonModule bean is registered regardless of which configuration class
    // declared it. SimpleModule implements JacksonModule.
    @Bean
    JacksonModule bigDecimalStringModule() {
        return new BigDecimalStringModule();
    }
}
