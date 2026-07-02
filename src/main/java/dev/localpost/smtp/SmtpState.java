package dev.localpost.smtp;

/**
 * The state of an SMTP session — determines which commands are valid next.
 * See RFC 5321 §3.3 for the conversation flow.
 */
public enum SmtpState {

    /** TCP connected, we've sent 220, waiting for HELO/EHLO. */
    CONNECTED,

    /** Client has greeted. Waiting for MAIL FROM (or QUIT). */
    GREETED,

    /** MAIL FROM received. Waiting for RCPT TO. */
    MAIL,

    /** At least one RCPT TO received. Waiting for more RCPT TO, or DATA. */
    RCPT,

    /** DATA received, client is streaming the message body. Waiting for lone dot. */
    DATA_RECEIVING
}