package com.easycrm.platform.money;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;

@Configuration
public class MoneyJacksonConfig {

    // Boot 4's Jackson auto-config discovers JacksonModule beans and registers them on the
    // application ObjectMapper. SimpleModule implements JacksonModule.
    @Bean
    JacksonModule bigDecimalStringModule() {
        return new BigDecimalStringModule();
    }
}
