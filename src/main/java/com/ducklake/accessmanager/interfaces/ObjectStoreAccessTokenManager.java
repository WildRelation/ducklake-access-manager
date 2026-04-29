package com.ducklake.accessmanager.interfaces;

import com.ducklake.accessmanager.model.AccessKey;
import java.util.List;

public interface ObjectStoreAccessTokenManager {

    AccessKey createReadOnlyKey(String bucketName, String keyName);

    AccessKey createReadWriteKey(String bucketName, String keyName);

    void deleteKey(String keyId);

    List<AccessKey> listKeys();
}
