package com.ducklake.accessmanager.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record BucketGrant(
    String studentEmail,
    UUID bucketId,
    String bucketName,
    String bucketDescription,
    LocalDateTime grantedAt
) {}
