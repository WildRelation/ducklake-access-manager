package com.ducklake.accessmanager.interfaces;

import com.ducklake.accessmanager.model.AccessKey;
import java.util.List;

/**
 * Hanterar generering och borttagning av åtkomstnycklar för objektlagring (S3-kompatibel).
 *
 * Implementeras av {@link com.ducklake.accessmanager.implementations.GarageAccessTokenManager}
 * för produktion. Kan även implementeras för MinIO vid lokal utveckling.
 *
 * Varje nyckel kopplas till en specifik bucket och tilldelas antingen
 * läs- eller läs/skriv-behörighet.
 */
public interface ObjectStoreAccessTokenManager {

    /**
     * Skapar en nyckel med enbart läsbehörighet (GET) för angiven bucket.
     *
     * @param bucketName namn på den bucket nyckeln ska ha åtkomst till
     * @param keyName    ett beskrivande namn för nyckeln
     * @return {@link AccessKey} med keyId, secretKey och endpoint
     */
    AccessKey createReadOnlyKey(String bucketName, String keyName);

    /**
     * Skapar en nyckel med läs- och skrivbehörighet (GET, PUT, DELETE) för angiven bucket.
     * Får endast anropas av privilegierade användare.
     *
     * @param bucketName namn på den bucket nyckeln ska ha åtkomst till
     * @param keyName    ett beskrivande namn för nyckeln
     * @return {@link AccessKey} med keyId, secretKey och endpoint
     */
    AccessKey createReadWriteKey(String bucketName, String keyName);

    /**
     * Tar bort en nyckel permanent från objektlagringen.
     *
     * @param keyId det unika ID:t för nyckeln som ska tas bort
     */
    void deleteKey(String keyId);

    /**
     * Listar alla nycklar som finns registrerade i objektlagringen.
     *
     * @return lista av {@link AccessKey}
     */
    List<AccessKey> listKeys();
}
