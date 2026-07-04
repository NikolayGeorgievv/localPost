package dev.localpost.message;

import org.apache.james.mime4j.dom.*;
import org.apache.james.mime4j.dom.address.AddressList;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.dom.address.MailboxList;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.mime4j.stream.MimeConfig;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a raw RFC 5322 message body (the payload that came in between
 * DATA and the terminating dot) into a structured {@link ParsedMessage}.
 *
 * Stateless - one instance can parse many messages concurrently.
 * The envelope info is supplied by the caller (the SMTP layer), since
 * envelope data isn't in the message body itself.
 */
public class MessageParser {

    private static final Logger LOG = Logger.getLogger(MessageParser.class);

    private static final String ATTACHMENT = "attachment";
    private static final String MIME_TYPE = "application/octet-stream";
    private static final String TEXT_HTML = "text/html";
    private static final String TEXT_PLAIN = "text/plain";

    private final DefaultMessageBuilder builder;

    public MessageParser() {
        this.builder = new DefaultMessageBuilder();
        // Be lenient with malformed messages - real-world email is messy,
        // and as a dev tool we'd rather parse a broken message than reject it.
        this.builder.setMimeEntityConfig(MimeConfig.PERMISSIVE);
    }

    /**
     * Parse a raw message payload and combine it with SMTP envelope data
     * into a structured ParsedMessage.
     *
     * @param rawSource the raw DATA payload (after dot-unstuffing, before MIME parsing)
     * @param envelope  the SMTP envelope info captured during the conversation
     * @return the parsed message, or null if parsing fails catastrophically
     */
    public ParsedMessage parse(String rawSource, Envelope envelope) {
        try {
            Message mimeMessage = builder.parseMessage(
                    new ByteArrayInputStream(rawSource.getBytes(StandardCharsets.UTF_8))
            );

            List<String> from = extractMailboxes(mimeMessage.getFrom());
            List<String> to = extractAddresses(mimeMessage.getTo());
            List<String> cc = extractAddresses(mimeMessage.getCc());
            String subject = mimeMessage.getSubject();
            Instant date = mimeMessage.getDate() != null
                    ? mimeMessage.getDate().toInstant()
                    : null;
            String messageId = mimeMessage.getMessageId();

            BodyWalkResult body = walkBody(mimeMessage);

            return new ParsedMessage(
                    envelope,
                    from,
                    to,
                    cc,
                    subject,
                    date,
                    messageId,
                    body.textBody,
                    body.htmlBody,
                    body.attachments,
                    rawSource
            );

        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse message");
            return null;
        }
    }

    /**
     * Convert a MailboxList (from getFrom()) into a plain list of addresses.
     * Returns null if the header was absent - for From this is a meaningful
     * distinction (see nullability convention in ParsedMessage).
     */
    private List<String> extractMailboxes(MailboxList mailboxes) {
        if (mailboxes == null) {
            return null;
        }
        return mailboxes.stream()
                .map(Mailbox::getAddress)
                .toList();
    }

    /**
     * Convert an AddressList (from getTo()/getCc()) into a plain list of addresses.
     * Flattens groups. Returns empty list if the header was absent - for To/Cc,
     * empty and missing are treated the same downstream.
     */
    private List<String> extractAddresses(AddressList addresses) {
        if (addresses == null) {
            return List.of();
        }
        return addresses.flatten().stream()
                .map(Mailbox::getAddress)
                .toList();
    }

    /**
     * Recursively walk the message body, classifying each leaf entity into
     * textBody, htmlBody, or an attachment.
     */
    private BodyWalkResult walkBody(Entity entity) {
        BodyWalkResult result = new BodyWalkResult();
        visit(entity, result);
        return result;
    }

    private void visit(Entity entity, BodyWalkResult result) {
        Body body = entity.getBody();

        if (body instanceof Multipart multipart) {
            // Container node - recurse into every child
            for (Entity part : multipart.getBodyParts()) {
                visit(part, result);
            }
            return;
        }

        // Leaf node - classify it
        classifyLeaf(entity, result);
    }

    private void classifyLeaf(Entity entity, BodyWalkResult result) {
        String mimeType = entity.getMimeType();
        String filename = entity.getFilename();
        String disposition = entity.getDispositionType();

        boolean isAttachment = ATTACHMENT.equalsIgnoreCase(disposition)
                || filename != null;

        if (isAttachment) {
            result.attachments.add(readAttachment(entity, mimeType, filename));
            return;
        }

        if (TEXT_PLAIN.equalsIgnoreCase(mimeType) && result.textBody == null) {
            result.textBody = readText(entity);
            return;
        }

        if (TEXT_HTML.equalsIgnoreCase(mimeType) && result.htmlBody == null) {
            result.htmlBody = readText(entity);
            return;
        }

        // Fallback: unknown content, preserve as an attachment so we don't lose it
        result.attachments.add(readAttachment(entity, mimeType, filename));
    }

    /**
     * Read the text content of a TextBody entity. Charset decoding is handled
     * by Mime4j via the Reader - we just consume characters.
     */
    private String readText(Entity entity) {
        if (!(entity.getBody() instanceof TextBody textBody)) {
            return null;
        }
        try {
            StringBuilder sb = new StringBuilder();
            try (var reader = textBody.getReader()) {
                char[] buf = new char[1024];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                }
            }
            return sb.toString();
        } catch (IOException e) {
            LOG.errorf(e, "Failed to read text body");
            return null;
        }
    }

    /**
     * Read the bytes of any leaf entity into an Attachment record.
     * Handles both BinaryBody (base64/etc-decoded) and TextBody (as bytes).
     */
    private Attachment readAttachment(Entity entity, String mimeType, String filename) {
        String contentType = mimeType != null ? mimeType : MIME_TYPE;
        byte[] content = readBytes(entity.getBody());
        return new Attachment(filename, contentType, content.length, content);
    }

    private byte[] readBytes(Body body) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (body instanceof BinaryBody binary) {
                binary.writeTo(out);
            } else if (body instanceof TextBody text) {
                text.writeTo(out);
            }
            return out.toByteArray();
        } catch (IOException e) {
            LOG.errorf(e, "Failed to read entity bytes");
            return new byte[0];
        }
    }

    private static class BodyWalkResult {
        String textBody = null;
        String htmlBody = null;
        final List<Attachment> attachments = new ArrayList<>();
    }
}