package com.ducklake.accessmanager.implementations;

import com.ducklake.accessmanager.interfaces.DatabaseAccessTokenManager;
import com.ducklake.accessmanager.model.DbCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

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

    @Override
    public DbCredentials createReadOnlyUser(String database) {
        String username = "dl_ro_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String password = UUID.randomUUID().toString();

        // TODO: CREATE USER, GRANT SELECT ON ALL TABLES IN SCHEMA public TO username
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public DbCredentials createReadWriteUser(String database) {
        String username = "dl_rw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String password = UUID.randomUUID().toString();

        // TODO: CREATE USER, GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO username
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void deleteUser(String username) {
        // TODO: REVOKE ALL PRIVILEGES, DROP USER
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public List<String> listUsers() {
        // TODO: SELECT usename FROM pg_user WHERE usename LIKE 'dl_%'
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
