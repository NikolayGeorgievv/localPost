package dev.localpost.smtp;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-connection SMTP session state. One instance lives for the duration
 * of one TCP connection. Not thread-safe — only accessed by the socket's
 * event loop thread.
 */
public class SmtpSession {

    private SmtpState state = SmtpState.CONNECTED;

    // Set when the client sends HELO/EHLO
    private String clientHostname;

    // Filled in over the course of one message (MAIL FROM, RCPT TO, DATA)
    private String sender;
    private final List<String> recipients = new ArrayList<>();
    private final StringBuilder messageBody = new StringBuilder();

    public SmtpState getState() {
        return state;
    }

    public void setState(SmtpState state) {
        this.state = state;
    }

    public String getClientHostname() {
        return clientHostname;
    }

    public void setClientHostname(String clientHostname) {
        this.clientHostname = clientHostname;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public List<String> getRecipients() {
        return recipients;
    }

    public void addRecipient(String recipient) {
        recipients.add(recipient);
    }

    public StringBuilder getMessageBody() {
        return messageBody;
    }

    public void appendBodyLine(String line) {
        messageBody.append(line).append("\r\n");
    }

    /**
     * Clear per-message state so the same connection can send another message.
     * Per RFC 5321, after a successful message the session returns to the
     * "greeted" state — the client can start a new MAIL FROM without re-doing HELO.
     */
    public void resetMessage() {
        sender = null;
        recipients.clear();
        messageBody.setLength(0);
        state = SmtpState.GREETED;
    }
}