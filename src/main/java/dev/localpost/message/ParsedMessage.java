package dev.localpost.message;

import java.time.Instant;
import java.util.List;

/**
 * A fully-parsed inbound message. Combines the SMTP envelope (from the
 * protocol layer) with the RFC 5322 headers and MIME body parts (parsed
 * out of the DATA payload by Mime4j).
 *
 * Nullability convention:
 *  - Fields from the untrusted MIME payload are nullable (from, subject,
 *    date, messageId, textBody, htmlBody).
 *  - Fields we control (envelope, rawSource) are never null.
 *  - Collections (to, cc, attachments) are never null — empty if absent.
 */
public record ParsedMessage(
        Envelope envelope,
        List<String> from,
        List<String> to,
        List<String> cc,
        String subject,
        Instant date,
        String messageId,
        String textBody,
        String htmlBody,
        List<Attachment> attachments,
        String rawSource
) {
}