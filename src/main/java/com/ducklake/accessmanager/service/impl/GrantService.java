package com.ducklake.accessmanager.service.impl;

import com.ducklake.accessmanager.model.BucketGrant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Service
public class GrantService {

    private final JdbcTemplate jdbc;

    public GrantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS student_grants (
                student_email VARCHAR NOT NULL,
                bucket_name   VARCHAR NOT NULL,
                granted_at    TIMESTAMP DEFAULT NOW(),
                PRIMARY KEY (student_email, bucket_name)
            )
        """);
    }

    public List<BucketGrant> listAll() {
        return jdbc.query(
            "SELECT student_email, bucket_name, granted_at FROM student_grants ORDER BY student_email, bucket_name",
            this::mapRow
        );
    }

    public List<BucketGrant> listForStudent(String studentEmail) {
        return jdbc.query(
            "SELECT student_email, bucket_name, granted_at FROM student_grants WHERE student_email = ? ORDER BY bucket_name",
            this::mapRow, studentEmail
        );
    }

    public void grant(String studentEmail, String bucketName) {
        jdbc.update(
            "INSERT INTO student_grants (student_email, bucket_name) VALUES (?, ?) ON CONFLICT DO NOTHING",
            studentEmail, bucketName
        );
    }

    public void revoke(String studentEmail, String bucketName) {
        jdbc.update(
            "DELETE FROM student_grants WHERE student_email = ? AND bucket_name = ?",
            studentEmail, bucketName
        );
    }

    public boolean hasGrant(String studentEmail, String bucketName) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM student_grants WHERE student_email = ? AND bucket_name = ?",
            Integer.class, studentEmail, bucketName
        );
        return count != null && count > 0;
    }

    public List<String> grantedBucketNames(String studentEmail) {
        return jdbc.queryForList(
            "SELECT bucket_name FROM student_grants WHERE student_email = ? ORDER BY bucket_name",
            String.class, studentEmail
        );
    }

    private BucketGrant mapRow(ResultSet rs, int i) throws SQLException {
        return new BucketGrant(
            rs.getString("student_email"),
            rs.getString("bucket_name"),
            rs.getTimestamp("granted_at").toLocalDateTime()
        );
    }
}
