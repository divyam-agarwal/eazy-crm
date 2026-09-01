package com.easycrm.iam.email;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LoggingEmailSenderTest {
    @Test
    void recordsLastSentEmail() {
        LoggingEmailSender sender = new LoggingEmailSender();
        sender.send("u@x.test", "Welcome", "Hi there");
        assertEquals("u@x.test", sender.lastTo());
        assertEquals("Welcome", sender.lastSubject());
    }
}
