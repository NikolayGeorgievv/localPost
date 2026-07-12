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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the single SQLite connection for the application.
 *
 * One connection, because exactly one thread touches the database. No pool.
 */
@ApplicationScoped
public class Database {

    private static final Logger LOG = Logger.getLogger(Database.class);

    private static final String JDBC_URL_PREFIX = "jdbc:sqlite:";
    private static final String EXPECTED_JOURNAL_MODE = "wal";

    /**
     * Bump whenever the schema changes. A database on any other version is
     * deleted and rebuilt from scratch - we hold throwaway test mail, so there
     * is nothing worth migrating.
     */
    private static final int SCHEMA_VERSION = 1;

    /** No meta table: either a brand-new file, or something we don't recognise. */
    private static final int NO_SCHEMA = 0;

    @ConfigProperty(name = "localpost.db.path")
    String dbPath;

    private Connection connection;

    void onStart(@Observes StartupEvent ev) throws SQLException, IOException {
        Path path = Path.of(dbPath).toAbsolutePath();
        Files.createDirectories(path.getParent());

        open(path);

        int found = readSchemaVersion();
        if (found != SCHEMA_VERSION) {
            reset(path, found);
        }

        createSchema();

        LOG.infof("SQLite ready: %s (schema v%d)", path, SCHEMA_VERSION);
    }

    private void open(Path path) throws SQLException {
        connection = DriverManager.getConnection(JDBC_URL_PREFIX + path);
        enableWalMode();
    }

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

    private int readSchemaVersion() throws SQLException {
        if (!tableExists("meta")) {
            return NO_SCHEMA;
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT value FROM meta WHERE key = 'schema_version'")) {

            return rs.next() ? Integer.parseInt(rs.getString(1)) : NO_SCHEMA;
        }
    }

    /** sqlite_master is SQLite's own catalogue of everything in the database. */
    private boolean tableExists(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {

            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Delete the database and reopen it empty. */
    private void reset(Path path, int foundVersion) throws SQLException, IOException {
        if (foundVersion == NO_SCHEMA) {
            LOG.infof("No existing schema - initialising a new database at v%d",
                    SCHEMA_VERSION);
        } else {
            LOG.warnf("Schema mismatch: database is v%d, code expects v%d. "
                            + "Rebuilding from scratch - all stored messages will be lost.",
                    foundVersion, SCHEMA_VERSION);
        }

        closeConnection();

        Files.deleteIfExists(path);
        Files.deleteIfExists(Path.of(path + "-wal"));
        Files.deleteIfExists(Path.of(path + "-shm"));

        open(path);
    }

    /**
     * Idempotent. Runs on every startup, whether or not we just rebuilt.
     */
    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS meta (
                        key   TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', ?)")) {

            ps.setString(1, String.valueOf(SCHEMA_VERSION));
            ps.executeUpdate();
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        closeConnection();
    }

    private void closeConnection() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
            LOG.debug("SQLite connection closed");
        } catch (SQLException e) {
            LOG.error("Failed to close SQLite connection", e);
        } finally {
            connection = null;
        }
    }
}