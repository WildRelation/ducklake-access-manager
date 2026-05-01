package com.ducklake.accessmanager.service;

import com.ducklake.accessmanager.model.DbCredentials;
import java.util.List;

/**
 * Manages creation and deletion of PostgreSQL users with the appropriate permissions.
 *
 * Implemented by {@link com.ducklake.accessmanager.service.impl.PostgresAccessTokenManager}
 * via JDBC. Users are created dynamically with random passwords and named with the
 * prefix "dl_ro_" (read-only) or "dl_rw_" (read/write).
 */
public interface DatabaseAccessTokenManager {

    /**
     * Creates a PostgreSQL user with SELECT-only permission on all tables.
     *
     * @return {@link DbCredentials} with username, password, and connection details
     */
    DbCredentials createReadOnlyUser();

    /**
     * Creates a PostgreSQL user with SELECT, INSERT, UPDATE, and DELETE permission.
     * Should only be called for privileged users.
     *
     * @return {@link DbCredentials} with username, password, and connection details
     */
    DbCredentials createReadWriteUser();

    /**
     * Deletes a PostgreSQL user and revokes all its privileges.
     *
     * @param username the username to delete
     */
    void deleteUser(String username);

    /**
     * Lists all dynamically created users (those with the "dl_" prefix).
     *
     * @return list of usernames
     */
    List<String> listUsers();
}
