package com.easycrm.iam.email;

public interface EmailSender {
    void send(String to, String subject, String body);
}
