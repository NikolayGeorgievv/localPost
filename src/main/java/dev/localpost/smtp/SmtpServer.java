package dev.localpost.smtp;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SmtpServer {

    private static final Logger LOG = Logger.getLogger(SmtpServer.class);
    private static final int PORT = 1025;

    @Inject
    Vertx vertx;

    private NetServer server;
    private final SmtpProtocol protocol = new SmtpProtocol();

    void onStart(@Observes StartupEvent ev) {
        server = vertx.createNetServer();

        server.connectHandler(this::handleConnection);

        server.listen(PORT)
                .onSuccess(s -> LOG.infof("SMTP server listening on port %d", PORT))
                .onFailure(err -> LOG.errorf(err, "Failed to start SMTP server on port %d", PORT));
    }

    private void handleConnection(NetSocket socket) {
        LOG.infof("Client connected: %s", socket.remoteAddress());

        // Each connection gets its own session and line buffer
        SmtpSession session = new SmtpSession();
        LineBuffer lineBuffer = new LineBuffer();

        // Greet the client immediately
        socket.write(SmtpProtocol.GREETING);

        socket.handler(buffer -> {
            lineBuffer.append(buffer);

            // Drain all complete lines from the buffer
            String line;
            while ((line = lineBuffer.nextLine()) != null) {
                SmtpProtocol.Response response = protocol.handleLine(session, line);

                if (response == null) {
                    // Silent accept (mid-DATA body line)
                    continue;
                }

                socket.write(response.text);

                if (response.closeAfter) {
                    socket.close();
                    break;
                }
            }
        });

        socket.closeHandler(v -> LOG.infof("Client disconnected: %s", socket.remoteAddress()));
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (server != null) {
            server.close();
            LOG.info("SMTP server stopped");
        }
    }
}