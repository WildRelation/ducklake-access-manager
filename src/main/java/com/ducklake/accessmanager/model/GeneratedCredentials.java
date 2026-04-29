package com.ducklake.accessmanager.model;

public record GeneratedCredentials(
    AccessKey s3Key,
    DbCredentials dbCredentials,
    String duckdbScript
) {}
