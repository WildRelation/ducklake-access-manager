package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.model.Bucket;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Service
public class BucketService {

    private final JdbcTemplate jdbc;

    public BucketService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS buckets (
                id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                name        VARCHAR NOT NULL UNIQUE,
                description TEXT,
                created_at  TIMESTAMP DEFAULT NOW()
            )
        """);
    }

    public List<Bucket> listAll() {
        return jdbc.query("SELECT * FROM buckets ORDER BY name", this::mapRow);
    }

    public List<Bucket> listGrantedFor(String studentEmail) {
        return jdbc.query("""
            SELECT b.* FROM buckets b
            JOIN bucket_grants g ON b.id = g.bucket_id
            WHERE g.student_email = ?
            ORDER BY b.name
            """, this::mapRow, studentEmail);
    }

    public Bucket add(String name, String description) {
        if (name == null || !name.matches("[a-z0-9][a-z0-9\\-]{1,61}[a-z0-9]")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid bucket name");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO buckets (id, name, description) VALUES (?, ?, ?)", id, name, description);
        return listAll().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM buckets WHERE id = ?", id);
    }

    private Bucket mapRow(ResultSet rs, int i) throws SQLException {
        return new Bucket(
            (UUID) rs.getObject("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getTimestamp("created_at").toLocalDateTime()
        );
    }
}
