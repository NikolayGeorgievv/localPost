package dev.localpost.smtp;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Future;
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

        SmtpSession session = new SmtpSession();
        LineBuffer lineBuffer = new LineBuffer();

        socket.write(SmtpProtocol.GREETING);

        socket.handler(buffer -> {
            lineBuffer.append(buffer);
            drain(socket, session, lineBuffer);
        });

        socket.closeHandler(v -> LOG.infof("Client disconnected: %s", socket.remoteAddress()));
    }

    /**
     * Pull complete lines out of the buffer and dispatch them, one at a time.
     *
     * If a response is not immediately available, we stop: no further lines are
     * dispatched and the socket is paused. The completion callback resumes and
     * re-enters this method. This preserves strict request/response ordering even
     * if the client pipelines commands.
     */
    private void drain(NetSocket socket, SmtpSession session, LineBuffer lineBuffer) {

        String line;
        while ((line = lineBuffer.nextLine()) != null) {

            Future<SmtpProtocol.Response> future = protocol.handleLine(session, line);

            // Fast path: answer already known. Stay in the loop, no thread hop.
            if (future.isComplete()) {
                if (!writeResponse(socket, future.result())) {
                    return;   // connection is closing
                }
                continue;
            }

            // Slow path: the protocol is waiting on something (a DB commit).
            // Stop taking new bytes, and stop dispatching buffered ones.
            socket.pause();

            future.onComplete(ar -> {
                if (ar.succeeded()) {
                    if (!writeResponse(socket, ar.result())) {
                        return;
                    }
                } else {
                    // Protocol layer is expected to recover failures into a 451.
                    // Reaching here means a bug - fail loudly, don't hang the client.
                    LOG.error("Unhandled failure in protocol layer", ar.cause());
                    socket.write(SmtpProtocol.INTERNAL_ERROR);
                }

                socket.resume();
                drain(socket, session, lineBuffer);
            });
            // the callback owns the loop from here
            return;
        }
    }

    /**
     * @return true to keep draining, false if the connection is closing.
     */
    private boolean writeResponse(NetSocket socket, SmtpProtocol.Response response) {
        if (response == SmtpProtocol.Response.NONE) {
            // mid-DATA body line - nothing to send
            return true;
        }

        socket.write(response.text());

        if (response.closeAfter()) {
            socket.close();
            return false;
        }
        return true;
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (server != null) {
            server.close();
            LOG.info("SMTP server stopped");
        }
    }
}