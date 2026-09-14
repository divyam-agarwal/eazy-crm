package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The shipped default is OPEN in every environment (spec F0-3). Reads application.yml directly. */
class SignupDefaultsTest {

    @Test
    void signupShipsOpen() throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(s -> env.getPropertySources().addLast(s));
        // Binder.get(env), not `new Binder(ConfigurationPropertySources.get(env))`: the latter
        // defaults to PlaceholdersResolver.NONE and would try to convert the literal string
        // "${SIGNUP_ENABLED:true}" straight to boolean. The static factory wires a
        // PropertySourcesPlaceholdersResolver so the ${VAR:default} syntax actually resolves.
        SignupProperties props = Binder.get(env)
                .bind("easycrm.signup", SignupProperties.class)
                .orElseThrow(() -> new AssertionError("easycrm.signup is not configured in application.yml"));
        assertTrue(props.enabled());
    }
}
