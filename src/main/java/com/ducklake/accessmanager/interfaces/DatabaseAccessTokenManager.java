package com.ducklake.accessmanager.interfaces;

import com.ducklake.accessmanager.model.DbCredentials;
import java.util.List;

/**
 * Hanterar generering och borttagning av PostgreSQL-användare med rätt behörigheter.
 *
 * Implementeras av {@link com.ducklake.accessmanager.implementations.PostgresAccessTokenManager}
 * via JDBC. Användarna skapas dynamiskt med slumpmässiga lösenord och namnges
 * med prefix "dl_ro_" (read-only) eller "dl_rw_" (read/write).
 */
public interface DatabaseAccessTokenManager {

    /**
     * Skapar en PostgreSQL-användare med enbart SELECT-behörighet på alla tabeller.
     *
     * @return {@link DbCredentials} med användarnamn, lösenord och anslutningsdetaljer
     */
    DbCredentials createReadOnlyUser();

    /**
     * Skapar en PostgreSQL-användare med SELECT, INSERT, UPDATE och DELETE-behörighet.
     * Får endast anropas av privilegierade användare.
     *
     * @return {@link DbCredentials} med användarnamn, lösenord och anslutningsdetaljer
     */
    DbCredentials createReadWriteUser();

    /**
     * Tar bort en PostgreSQL-användare och återkallar alla dess behörigheter.
     *
     * @param username användarnamnet som ska tas bort
     */
    void deleteUser(String username);

    /**
     * Listar alla dynamiskt skapade användare (de med prefix "dl_").
     *
     * @return lista av användarnamn
     */
    List<String> listUsers();
}
