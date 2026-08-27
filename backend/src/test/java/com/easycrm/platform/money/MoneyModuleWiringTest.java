package com.easycrm.platform.money;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JacksonModule;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MoneyAutoConfiguration is registered through AutoConfiguration.imports AND sits inside the
 * com.easycrm package that EasyCrmApplication component-scans, so it is reachable twice. Spring
 * de-duplicates configuration classes by class name, but "should" is not "does" — this test is
 * the proof, and it fails loudly (context refresh) rather than silently if that ever changes.
 */
class MoneyModuleWiringTest extends IntegrationTest {

    @Autowired ApplicationContext ctx;

    @Test
    void exactlyOneBigDecimalStringModuleBeanIsRegistered() {
        Map<String, JacksonModule> modules = ctx.getBeansOfType(JacksonModule.class);

        assertThat(modules.values())
            .filteredOn(BigDecimalStringModule.class::isInstance)
            .as("registered twice = a duplicate-definition trap; zero = MB1, money on the wire "
              + "as a JSON number with no error anywhere")
            .hasSize(1);
    }

    @Test
    void theAutoConfigurationIsWhatRegisteredIt() {
        assertThat(ctx.getBeanDefinitionNames())
            .as("the bean must arrive through auto-configuration, not component scan, or it "
              + "disappears the day a service scans from com.easycrm.sales instead")
            .contains("com.easycrm.platform.money.MoneyAutoConfiguration");
    }
}
