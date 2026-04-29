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
 * Implementationsordning:
 *   Steg 1 – createReadOnlyUser:  CREATE USER + GRANT SELECT
 *   Steg 2 – createReadWriteUser: CREATE USER + GRANT SELECT, INSERT, UPDATE, DELETE
 *   Steg 3 – deleteUser:          REVOKE ALL + DROP USER
 *   Steg 4 – listUsers:           SELECT från pg_user WHERE usename LIKE 'dl_%'
 */
@Service
public class PostgresAccessTokenManager implements DatabaseAccessTokenManager {

    private final JdbcTemplate jdbcTemplate;
    private final String dbHost;
    private final int dbPort;
    private final String dbName;

    public PostgresAccessTokenManager(
        JdbcTemplate jdbcTemplate,
        @Value("${spring.datasource.host}") String dbHost,
        @Value("${spring.datasource.port:5432}") int dbPort,
        @Value("${spring.datasource.dbname}") String dbName
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.dbHost = dbHost;
        this.dbPort = dbPort;
        this.dbName = dbName;
    }

    /**
     * Steg 1: Skapa en PostgreSQL-användare med enbart läsbehörighet.
     *
     * SQL som ska köras via jdbcTemplate.execute():
     *   CREATE USER {username} WITH PASSWORD '{password}';
     *   GRANT CONNECT ON DATABASE {database} TO {username};
     *   GRANT USAGE ON SCHEMA public TO {username};
     *   GRANT SELECT ON ALL TABLES IN SCHEMA public TO {username};
     */
    @Override
    public DbCredentials createReadOnlyUser(String database) {
        String username = "dl_ro_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String password = UUID.randomUUID().toString();

        // TODO: implementera enligt SQL ovan
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Steg 2: Skapa en PostgreSQL-användare med läs- och skrivbehörighet.
     *
     * SQL som ska köras via jdbcTemplate.execute():
     *   CREATE USER {username} WITH PASSWORD '{password}';
     *   GRANT CONNECT ON DATABASE {database} TO {username};
     *   GRANT USAGE ON SCHEMA public TO {username};
     *   GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO {username};
     */
    @Override
    public DbCredentials createReadWriteUser(String database) {
        String username = "dl_rw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String password = UUID.randomUUID().toString();

        // TODO: implementera enligt SQL ovan
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Steg 3: Ta bort en PostgreSQL-användare och återkalla alla rättigheter.
     *
     * SQL som ska köras:
     *   REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM {username};
     *   REVOKE ALL ON SCHEMA public FROM {username};
     *   REVOKE CONNECT ON DATABASE {database} FROM {username};
     *   DROP USER {username};
     */
    @Override
    public void deleteUser(String username) {
        // TODO: implementera enligt SQL ovan
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Steg 4: Lista alla dynamiskt skapade användare (prefix "dl_").
     *
     * SQL: SELECT usename FROM pg_user WHERE usename LIKE 'dl_%'
     */
    @Override
    public List<String> listUsers() {
        // TODO: implementera enligt SQL ovan
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
