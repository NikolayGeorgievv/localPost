package dev.localpost.data;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the single SQLite connection for the application.
 *
 * There is exactly one connection because there is exactly one thread that
 * touches the database. No pool, by design.
 */
@ApplicationScoped
public class Database {

    private static final Logger LOG = Logger.getLogger(Database.class);

    private static final String JDBC_URL_PREFIX = "jdbc:sqlite:";
    private static final String EXPECTED_JOURNAL_MODE = "wal";

    @ConfigProperty(name = "localpost.db.path")
    String dbPath;

    private Connection connection;

    void onStart(@Observes StartupEvent ev) throws SQLException, IOException {
        Path path = Path.of(dbPath).toAbsolutePath();

        // SQLite will NOT create the parent directory - it just fails with an
        // opaque "unable to open database file".
        Files.createDirectories(path.getParent());

        connection = DriverManager.getConnection(JDBC_URL_PREFIX + path);
        enableWalMode();

        LOG.infof("SQLite database opened: %s", path);
    }

    /**
     * WAL lets readers run while the writer is mid-insert.
     * The default rollback journal would lock them out.
     *
     * This setting is persisted in the database file, so it survives restarts —
     * but re-applying it is harmless and keeps the intent visible in code.
     */
    private void enableWalMode() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA journal_mode = WAL")) {

            String mode = rs.next() ? rs.getString(1) : "unknown";

            if (!EXPECTED_JOURNAL_MODE.equalsIgnoreCase(mode)) {
                LOG.warnf("Expected journal mode '%s' but SQLite reports '%s'. "
                        + "Concurrent reads may block.", EXPECTED_JOURNAL_MODE, mode);
            }
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
            LOG.info("SQLite database closed");
        } catch (SQLException e) {
            LOG.error("Failed to close SQLite connection", e);
        }
    }
}