package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.service.DatabaseAccessTokenManager;
import com.ducklake.accessmanager.model.DbCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Implements {@link DatabaseAccessTokenManager} against PostgreSQL via JDBC.
 *
 * Connects to the ducklake-catalog deployment with admin credentials and
 * creates dynamic users following the principle of least privilege.
 *
 * Users are named automatically:
 *   - Read-only:  "dl_ro_" + 8 random hex chars  (e.g. dl_ro_a3f2b1c9)
 *   - Read/write: "dl_rw_" + 8 random hex chars  (e.g. dl_rw_7e4d8f2a)
 *
 * Note: DDL statements (CREATE USER, GRANT, DROP USER) do not support prepared statement
 * parameters in PostgreSQL. Usernames and passwords are interpolated directly into the SQL,
 * but are safe because both are generated programmatically via UUID (no user input involved).
 */
@Service
public class PostgresAccessTokenManager implements DatabaseAccessTokenManager {

    // Hardcoded instead of @Value because Spring Boot's relaxed binding maps Kubernetes
    // service env vars (e.g. DUCKLAKE_CATALOG_PORT=tcp://10.x.x.x:5432, injected for the
    // ducklake-catalog service) to Spring properties, overriding any configured value.
    // PostgreSQL always runs on 5432 in this setup, so a constant is the correct choice.
    private static final int DB_PORT = 5432;

    private final JdbcTemplate jdbcTemplate;
    private final String dbHost;
    private final String dbName;

    public PostgresAccessTokenManager(
        JdbcTemplate jdbcTemplate,
        @Value("${ducklake.postgres.host}") String dbHost,
        @Value("${ducklake.postgres.dbname}") String dbName
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.dbHost = dbHost;
        this.dbName = dbName;
    }

    /**
     * Creates a PostgreSQL user with SELECT-only permission.
     * Grants CONNECT on the database, USAGE on the schema, and SELECT on all tables.
     */
    @Override
    public DbCredentials createReadOnlyUser() {
        String username = "dl_ro_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String password = UUID.randomUUID().toString();

        jdbcTemplate.execute("CREATE USER " + username + " WITH PASSWORD '" + password + "'");
        jdbcTemplate.execute("GRANT CONNECT ON DATABASE " + dbName + " TO " + username);
        jdbcTemplate.execute("GRANT USAGE ON SCHEMA public TO " + username);
        jdbcTemplate.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + username);

        return new DbCredentials(username, password, dbHost, DB_PORT, dbName, "read");
    }

    /**
     * Creates a PostgreSQL user with SELECT, INSERT, UPDATE, and DELETE permission.
     * Grants CONNECT on the database, USAGE on the schema, and full DML on all tables.
     */
    @Override
    public DbCredentials createReadWriteUser() {
        String username = "dl_rw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String password = UUID.randomUUID().toString();

        jdbcTemplate.execute("CREATE USER " + username + " WITH PASSWORD '" + password + "'");
        jdbcTemplate.execute("GRANT CONNECT ON DATABASE " + dbName + " TO " + username);
        jdbcTemplate.execute("GRANT USAGE ON SCHEMA public TO " + username);
        jdbcTemplate.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + username);

        return new DbCredentials(username, password, dbHost, DB_PORT, dbName, "readwrite");
    }

    /**
     * Deletes a PostgreSQL user and revokes all its privileges.
     * Privileges must be revoked before DROP USER can be executed.
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
     * Lists all dynamically created users with the "dl_" prefix.
     */
    @Override
    public List<String> listUsers() {
        return jdbcTemplate.queryForList(
            "SELECT usename FROM pg_user WHERE usename LIKE 'dl_%'",
            String.class
        );
    }

    // Safety check: only allow deletion of users with the "dl_" prefix
    // to prevent accidental removal of the admin account or other system users
    private void validateUsername(String username) {
        if (username == null || !username.matches("dl_(ro|rw)_[a-f0-9]{8}")) {
            throw new IllegalArgumentException("Invalid username format: " + username);
        }
    }
}
