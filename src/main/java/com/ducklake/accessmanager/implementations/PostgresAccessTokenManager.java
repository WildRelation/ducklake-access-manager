package com.ducklake.accessmanager.implementations;

import com.ducklake.accessmanager.interfaces.DatabaseAccessTokenManager;
import com.ducklake.accessmanager.model.DbCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Implementerar {@link DatabaseAccessTokenManager} mot PostgreSQL via JDBC.
 *
 * Ansluter till ducklake-catalog-deploymentet med admin-behörighet och
 * skapar dynamiska användare med minimala rättigheter (principle of least privilege).
 *
 * Användare namnges automatiskt:
 *   - Read-only:  "dl_ro_" + 8 slumptecken  (ex: dl_ro_a3f2b1c9)
 *   - Read/write: "dl_rw_" + 8 slumptecken  (ex: dl_rw_7e4d8f2a)
 *
 * OBS: DDL-satser (CREATE USER, GRANT, DROP USER) stödjer inte prepared statement-parametrar
 * i PostgreSQL. Användarnamn och lösenord sätts direkt i SQL-strängen, men är säkra eftersom
 * båda genereras programmatiskt via UUID (ingen användarinmatning involverad).
 */
@Service
public class PostgresAccessTokenManager implements DatabaseAccessTokenManager {

    private final JdbcTemplate jdbcTemplate;
    private final String dbHost;
    private final int dbPort;
    private final String dbName;

    public PostgresAccessTokenManager(
        JdbcTemplate jdbcTemplate,
        @Value("${ducklake.postgres.host}") String dbHost,
        @Value("${ducklake.postgres.port:5432}") int dbPort,
        @Value("${ducklake.postgres.dbname}") String dbName
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.dbHost = dbHost;
        this.dbPort = dbPort;
        this.dbName = dbName;
    }

    /**
     * Skapar en PostgreSQL-användare med enbart SELECT-behörighet.
     * Användaren får CONNECT på databasen, USAGE på schemat och SELECT på alla tabeller.
     */
    @Override
    public DbCredentials createReadOnlyUser() {
        String username = "dl_ro_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String password = UUID.randomUUID().toString();

        jdbcTemplate.execute("CREATE USER " + username + " WITH PASSWORD '" + password + "'");
        jdbcTemplate.execute("GRANT CONNECT ON DATABASE " + dbName + " TO " + username);
        jdbcTemplate.execute("GRANT USAGE ON SCHEMA public TO " + username);
        jdbcTemplate.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + username);

        return new DbCredentials(username, password, dbHost, dbPort, dbName, "read");
    }

    /**
     * Skapar en PostgreSQL-användare med SELECT, INSERT, UPDATE och DELETE-behörighet.
     * Användaren får CONNECT på databasen, USAGE på schemat och full DML på alla tabeller.
     */
    @Override
    public DbCredentials createReadWriteUser() {
        String username = "dl_rw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String password = UUID.randomUUID().toString();

        jdbcTemplate.execute("CREATE USER " + username + " WITH PASSWORD '" + password + "'");
        jdbcTemplate.execute("GRANT CONNECT ON DATABASE " + dbName + " TO " + username);
        jdbcTemplate.execute("GRANT USAGE ON SCHEMA public TO " + username);
        jdbcTemplate.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + username);

        return new DbCredentials(username, password, dbHost, dbPort, dbName, "readwrite");
    }

    /**
     * Tar bort en PostgreSQL-användare och återkallar alla dess rättigheter.
     * Rättigheter måste återkallas innan DROP USER kan köras.
     */
    @Override
    public void deleteUser(String username) {
        validateUsername(username);

        jdbcTemplate.execute("REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM " + username);
        jdbcTemplate.execute("REVOKE ALL ON SCHEMA public FROM " + username);
        jdbcTemplate.execute("REVOKE CONNECT ON DATABASE " + dbName + " FROM " + username);
        jdbcTemplate.execute("DROP USER " + username);
    }

    /**
     * Listar alla dynamiskt skapade användare med prefix "dl_".
     */
    @Override
    public List<String> listUsers() {
        return jdbcTemplate.queryForList(
            "SELECT usename FROM pg_user WHERE usename LIKE 'dl_%'",
            String.class
        );
    }

    // Säkerhetskontroll: tillåt bara borttagning av användare med prefixet "dl_"
    // för att förhindra att admin-kontot eller andra systemanvändare råkar tas bort
    private void validateUsername(String username) {
        if (username == null || !username.matches("dl_(ro|rw)_[a-f0-9]{8}")) {
            throw new IllegalArgumentException("Invalid username format: " + username);
        }
    }
}
