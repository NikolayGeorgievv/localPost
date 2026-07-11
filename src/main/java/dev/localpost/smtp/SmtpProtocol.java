package dev.localpost.smtp;

import dev.localpost.message.Envelope;
import dev.localpost.message.MessageParser;
import dev.localpost.message.ParsedMessage;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * The SMTP state machine. Given a session and a line from the client,
 * decide what to do: update the session, produce a response.
 *
 * Stateless — one instance can serve all connections. Each connection's
 * state lives in its own SmtpSession.
 */
public class SmtpProtocol {

    private static final Logger LOG = Logger.getLogger(SmtpProtocol.class);

    public static final String GREETING = "220 localpost ESMTP ready\r\n";

    private final MessageParser messageParser = new MessageParser();

    /**
     * Process one incoming line. Updates session, returns response to send.
     */
    public Response handleLine(SmtpSession session, String line) {

        // During DATA_RECEIVING, lines are message body, not commands
        if (session.getState() == SmtpState.DATA_RECEIVING) {
            return handleDataLine(session, line);
        }

        // Otherwise, parse the verb and dispatch
        String verb = extractVerb(line);
        return switch (verb) {
            case "HELO", "EHLO" -> handleHelo(session, line);
            case "MAIL" -> handleMailFrom(session, line);
            case "RCPT" -> handleRcptTo(session, line);
            case "DATA" -> handleData(session);
            case "QUIT" -> handleQuit();
            case "RSET" -> handleRset(session);
            case "NOOP" -> Response.reply("250 OK\r\n");
            default -> Response.reply("500 5.5.2 Command unrecognized\r\n");
        };
    }

    private String extractVerb(String line) {
        // Verb is the first whitespace-delimited token, uppercased
        int space = line.indexOf(' ');
        String verb = (space == -1) ? line : line.substring(0, space);
        return verb.toUpperCase();
    }

    private Response handleHelo(SmtpSession session, String line) {
        if (session.getState() != SmtpState.CONNECTED
                && session.getState() != SmtpState.GREETED) {
            return Response.reply("503 5.5.1 Bad sequence of commands\r\n");
        }

        // Extract the hostname argument
        int space = line.indexOf(' ');
        String hostname = (space == -1) ? "unknown" : line.substring(space + 1).trim();

        session.setClientHostname(hostname);
        session.setState(SmtpState.GREETED);

        LOG.infof("Client greeted: %s", hostname);
        return Response.reply("250 localpost Hello " + hostname + "\r\n");
    }

    private Response handleMailFrom(SmtpSession session, String line) {
        if (session.getState() != SmtpState.GREETED) {
            return Response.reply("503 5.5.1 Bad sequence of commands\r\n");
        }

        String address = extractAddress(line, "MAIL FROM:");
        if (address == null) {
            return Response.reply("501 5.5.4 Syntax error in MAIL FROM parameters\r\n");
        }

        session.setSender(address);
        session.setState(SmtpState.MAIL);

        LOG.infof("MAIL FROM: %s", address);
        return Response.reply("250 2.1.0 Sender OK\r\n");
    }

    private Response handleRcptTo(SmtpSession session, String line) {
        if (session.getState() != SmtpState.MAIL
                && session.getState() != SmtpState.RCPT) {
            return Response.reply("503 5.5.1 Bad sequence of commands\r\n");
        }

        String address = extractAddress(line, "RCPT TO:");
        if (address == null) {
            return Response.reply("501 5.5.4 Syntax error in RCPT TO parameters\r\n");
        }

        session.addRecipient(address);
        session.setState(SmtpState.RCPT);

        LOG.infof("RCPT TO: %s", address);
        return Response.reply("250 2.1.5 Recipient OK\r\n");
    }

    private Response handleData(SmtpSession session) {
        if (session.getState() != SmtpState.RCPT) {
            return Response.reply("503 5.5.1 Bad sequence of commands\r\n");
        }

        session.setState(SmtpState.DATA_RECEIVING);
        return Response.reply("354 End data with <CR><LF>.<CR><LF>\r\n");
    }

    private Response handleDataLine(SmtpSession session, String line) {
        // Lone dot signals end of message body
        if (line.equals(".")) {
            String rawBody = session.getMessageBody().toString();
            Envelope envelope = new Envelope(
                    session.getSender(),
                    List.copyOf(session.getRecipients())
            );

            ParsedMessage parsed = messageParser.parse(rawBody, envelope);

            if (parsed != null) {
                logParsedMessage(parsed);
            } else {
                LOG.warnf("Could not parse message from %s — raw body follows:\n%s",
                        envelope.sender(), rawBody);
            }

            session.resetMessage();
            return Response.reply("250 2.0.0 Message accepted\r\n");
        }

        // Dot-stuffing: line starting with ".." means literal ".", strip one dot
        if (line.startsWith("..")) {
            line = line.substring(1);
        }

        session.appendBodyLine(line);
        return Response.NONE; // no response during body streaming
    }

    private Response handleQuit() {
        return Response.replyAndClose("221 2.0.0 Bye\r\n");
    }

    private Response handleRset(SmtpSession session) {
        session.resetMessage();
        return Response.reply("250 2.0.0 OK\r\n");
    }

    /**
     * Extract the email address from a command like "MAIL FROM:<alice@example.com>".
     * Tolerant of the space that some clients add after the colon.
     */
    private String extractAddress(String line, String prefix) {
        // Case-insensitive prefix match
        if (line.length() < prefix.length()
                || !line.substring(0, prefix.length()).equalsIgnoreCase(prefix)) {
            return null;
        }

        String rest = line.substring(prefix.length()).trim();

        // Address should be wrapped in angle brackets: <alice@example.com>
        if (!rest.startsWith("<") || !rest.contains(">")) {
            return null;
        }

        int end = rest.indexOf('>');
        return rest.substring(1, end);
    }

    private void logParsedMessage(ParsedMessage msg) {
        LOG.infof("""
                    
                    === MESSAGE RECEIVED ===
                    Envelope from: %s
                    Envelope to:   %s
                    ---
                    Header From:    %s
                    Header To:      %s
                    Header Cc:      %s
                    Subject:        %s
                    Date:           %s
                    Message-ID:     %s
                    ---
                    Text body: %s
                    HTML body: %s
                    Attachments: %d
                    ========================""",
                msg.envelope().sender(),
                msg.envelope().recipients(),
                msg.from(),
                msg.to(),
                msg.cc(),
                msg.subject(),
                msg.date(),
                msg.messageId(),
                summarize(msg.textBody()),
                summarize(msg.htmlBody()),
                msg.attachments().size()
        );
    }

    private String summarize(String body) {
        if (body == null) {
            return "(none)";
        }
        String escaped = body.replace("\r", "\\r").replace("\n", "\\n");
        if (escaped.length() <= 80) {
            return escaped;
        }
        return escaped.substring(0, 80) + "... (" + body.length() + " chars)";
    }

    /**
     * A response to send back to the client.
     *
     * NONE is a sentinel meaning "the protocol has nothing to say" — used for
     * message body lines during DATA, which are silently accumulated. It exists
     * so that no part of the pipeline ever carries a null Response.
     */
    public record Response(String text, boolean closeAfter) {

        public static final Response NONE = new Response("", false);

        public static Response reply(String text) {
            return new Response(text, false);
        }

        public static Response replyAndClose(String text) {
            return new Response(text, true);
        }
    }
}