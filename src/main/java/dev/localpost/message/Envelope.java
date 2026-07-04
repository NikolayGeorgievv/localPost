package dev.localpost.message;

import java.util.List;

/**
 * SMTP envelope information - the addresses used by the protocol for routing,
 * captured from MAIL FROM and RCPT TO during the SMTP conversation.
 *
 * Distinct from the RFC 5322 headers (From/To/Cc) inside the message body,
 * which can differ (BCC being the classic example).
 */
public record Envelope(
        String sender,
        List<String> recipients
) {
}