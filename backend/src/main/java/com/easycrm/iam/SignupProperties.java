package com.easycrm.iam;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The self-serve signup switch (spec 2026-09-14-f0 F0-3). Defaults OPEN; close with
 * SIGNUP_ENABLED=false and a restart. A live toggle waits on the platform admin role (ROADMAP 4a).
 */
@ConfigurationProperties("easycrm.signup")
public record SignupProperties(@DefaultValue("true") boolean enabled) {}
