package com.ducklake.accessmanager.model;

public record AccessKey(
    String keyId,
    String secretKey,
    String bucketName,
    String permission,
    String endpoint
) {}
