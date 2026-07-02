package dev.localpost.smtp;

import io.vertx.core.buffer.Buffer;

/**
 * Accumulates bytes from a TCP stream and hands out complete CRLF-terminated lines.
 * Not thread-safe — meant to be owned by a single connection.
 */
public class LineBuffer {

    private static final byte CR = '\r';
    private static final byte LF = '\n';

    private Buffer buffer = Buffer.buffer();

    /**
     * Add bytes from the network. Call this from the socket's data handler.
     */
    public void append(Buffer bytes) {
        buffer.appendBuffer(bytes);
    }

    /**
     * Pull the next complete line out of the buffer, without the trailing CRLF.
     * Returns null if no complete line is available yet.
     */
    public String nextLine() {
        // Scan for the CRLF sequence
        for (int i = 0; i < buffer.length() - 1; i++) {
            if (buffer.getByte(i) == CR && buffer.getByte(i + 1) == LF) {
                // Extract the line (bytes before the CR)
                String line = buffer.getString(0, i);
                // Advance the buffer past the CRLF
                buffer = buffer.getBuffer(i + 2, buffer.length());
                return line;
            }
        }
        return null; // no complete line yet
    }
}