package com.ducklake.accessmanager.model;

public record KeyRequest(
    String bucketName,
    String permission  // "read" or "readwrite"
) {}
