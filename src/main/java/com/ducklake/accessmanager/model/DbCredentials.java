package com.ducklake.accessmanager.model;

public record DbCredentials(
    String username,
    String password,
    String host,
    int port,
    String database,
    String permission
) {}
