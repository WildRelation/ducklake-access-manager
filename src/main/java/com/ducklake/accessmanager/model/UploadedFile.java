package com.ducklake.accessmanager.model;

public record UploadedFile(
    int    id,
    String bucketName,
    String objectKey,
    String originalName,
    String tableName,
    long   fileSize,
    String uploadedBy,
    String uploadedAt
) {}
