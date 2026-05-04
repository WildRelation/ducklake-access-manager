package com.ducklake.accessmanager.model;

import java.time.LocalDateTime;

public record BucketGrant(
    String studentEmail,
    String bucketName,
    LocalDateTime grantedAt
) {}
