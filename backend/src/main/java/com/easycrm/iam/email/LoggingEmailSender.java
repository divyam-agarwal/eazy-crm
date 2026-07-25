package com.easycrm.iam.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** v1 stub: logs instead of sending. Replace with an SES/SMTP impl in a later plan. */
@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    private volatile String lastTo;
    private volatile String lastSubject;

    @Override
    public void send(String to, String subject, String body) {
        this.lastTo = to;
        this.lastSubject = subject;
        log.info("[email:stub] to={} subject={}", to, subject);
    }

    public String lastTo() { return lastTo; }
    public String lastSubject() { return lastSubject; }
}
