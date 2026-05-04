package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.model.BucketGrant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Service
public class GrantService {

    private final JdbcTemplate jdbc;

    // BucketService dependency ensures the buckets table exists before bucket_grants is created
    public GrantService(JdbcTemplate jdbc, BucketService bucketService) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS bucket_grants (
                student_email VARCHAR NOT NULL,
                bucket_id     UUID NOT NULL REFERENCES buckets(id) ON DELETE CASCADE,
                granted_at    TIMESTAMP DEFAULT NOW(),
                PRIMARY KEY (student_email, bucket_id)
            )
        """);
    }

    public List<BucketGrant> listAll() {
        return jdbc.query("""
            SELECT g.student_email, g.bucket_id, g.granted_at,
                   b.name AS bucket_name, b.description AS bucket_description
            FROM bucket_grants g
            JOIN buckets b ON b.id = g.bucket_id
            ORDER BY g.student_email, b.name
            """, this::mapRow);
    }

    public List<BucketGrant> listForStudent(String studentEmail) {
        return jdbc.query("""
            SELECT g.student_email, g.bucket_id, g.granted_at,
                   b.name AS bucket_name, b.description AS bucket_description
            FROM bucket_grants g
            JOIN buckets b ON b.id = g.bucket_id
            WHERE g.student_email = ?
            ORDER BY b.name
            """, this::mapRow, studentEmail);
    }

    public void grant(String studentEmail, UUID bucketId) {
        jdbc.update(
            "INSERT INTO bucket_grants (student_email, bucket_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            studentEmail, bucketId
        );
    }

    public void revoke(String studentEmail, UUID bucketId) {
        jdbc.update(
            "DELETE FROM bucket_grants WHERE student_email = ? AND bucket_id = ?",
            studentEmail, bucketId
        );
    }

    public boolean hasGrant(String studentEmail, String bucketName) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM bucket_grants g
            JOIN buckets b ON b.id = g.bucket_id
            WHERE g.student_email = ? AND b.name = ?
            """, Integer.class, studentEmail, bucketName);
        return count != null && count > 0;
    }

    private BucketGrant mapRow(ResultSet rs, int i) throws SQLException {
        return new BucketGrant(
            rs.getString("student_email"),
            (UUID) rs.getObject("bucket_id"),
            rs.getString("bucket_name"),
            rs.getString("bucket_description"),
            rs.getTimestamp("granted_at").toLocalDateTime()
        );
    }
}
