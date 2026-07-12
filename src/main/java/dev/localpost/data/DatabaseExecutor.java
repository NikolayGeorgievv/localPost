package dev.localpost.data;

import io.quarkus.runtime.ShutdownEvent;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs database work on a single dedicated thread.
 *
 * One thread, for two reasons:
 *   - SQLite allows one writer. Serialising here makes SQLITE_BUSY impossible
 *     rather than merely unlikely.
 *   - Messages are inserted in the order they arrived, by construction.
 *
 * The caller is on a Vert.x event loop. The work is not. Results are handed
 * back on the caller's original context, so callers never leave their thread.
 */
@ApplicationScoped
public class DatabaseExecutor {

    private static final Logger LOG = Logger.getLogger(DatabaseExecutor.class);
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    @Inject
    Vertx vertx;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "localpost-db");
                thread.setDaemon(false);
                return thread;
            });

    /**
     * Run {@code work} on the database thread.
     *
     * @return a Future completed on the caller's Vert.x context.
     */
    public <T> Future<T> execute(Callable<T> work) {
        Context context = vertx.getOrCreateContext();
        Promise<T> promise = Promise.promise();

        executor.execute(() -> {
            try {
                T result = work.call();
                context.runOnContext(v -> promise.complete(result));
            } catch (Exception e) {
                context.runOnContext(v -> promise.fail(e));
            }
        });

        return promise.future();
    }

    void onStop(@Observes ShutdownEvent ev) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOG.warnf("Database thread did not finish within %ds — forcing shutdown. "
                        + "In-flight writes may be lost.", SHUTDOWN_TIMEOUT_SECONDS);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        LOG.debug("Database thread stopped");
    }
}