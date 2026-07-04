package dev.localpost.message;

/**
 * A non-body part of a MIME message - typically identified by a
 * Content-Disposition: attachment header or a filename parameter.
 */
public record Attachment(
        String filename,
        String contentType,
        long sizeBytes,
        byte[] content
) {
}