package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.service.KeyMappingService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PostgresKeyMappingService implements KeyMappingService {

    private final JdbcTemplate jdbc;

    public PostgresKeyMappingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS key_user_mapping (
                garage_key_id VARCHAR PRIMARY KEY,
                keycloak_user VARCHAR NOT NULL,
                created_at    TIMESTAMP DEFAULT NOW()
            )
        """);
    }

    @Override
    public void saveMapping(String garageKeyId, String keycloakUser) {
        jdbc.update(
            "INSERT INTO key_user_mapping (garage_key_id, keycloak_user) VALUES (?, ?) ON CONFLICT DO NOTHING",
            garageKeyId, keycloakUser
        );
    }

    @Override
    public String findOwner(String garageKeyId) {
        List<String> rows = jdbc.queryForList(
            "SELECT keycloak_user FROM key_user_mapping WHERE garage_key_id = ?",
            String.class, garageKeyId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<String> findKeyIdsForUser(String keycloakUser) {
        return jdbc.queryForList(
            "SELECT garage_key_id FROM key_user_mapping WHERE keycloak_user = ?",
            String.class, keycloakUser
        );
    }

    @Override
    public void deleteMapping(String garageKeyId) {
        jdbc.update("DELETE FROM key_user_mapping WHERE garage_key_id = ?", garageKeyId);
    }
}
