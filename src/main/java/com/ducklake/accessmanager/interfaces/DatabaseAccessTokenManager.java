package com.ducklake.accessmanager.interfaces;

import com.ducklake.accessmanager.model.DbCredentials;
import java.util.List;

public interface DatabaseAccessTokenManager {

    DbCredentials createReadOnlyUser(String database);

    DbCredentials createReadWriteUser(String database);

    void deleteUser(String username);

    List<String> listUsers();
}
