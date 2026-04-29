# Implementationsguide – DuckLake Access Manager

Denna guide beskriver steg-för-steg hur tjänsten ska implementeras.
Koden är skriven på engelska; kommentarer och dokumentation på svenska.

---

## Förutsättningar

- Java 17+
- Maven 3.9+
- Tillgång till `ducklake-catalog` (PostgreSQL) och `ducklake-garage` (Garage) på cbhcloud
- Garage Admin Token (finns i deployment-inställningarna för `ducklake-garage`)

---

## Fas 1 – GarageAccessTokenManager ✅ Implementerad

**Fil:** `implementations/GarageAccessTokenManager.java`

Garage Admin API v2 körs internt på port 3903 i `ducklake-garage`-deploymentet.
Alla anrop autentiseras med en Bearer-token via `Authorization`-headern.

> **OBS:** Garage använder API v2. Endpoints börjar med `/v2/` och använder camelCase-namn.

### Steg 1.1 – Skapa en nyckel (createReadOnlyKey / createReadWriteKey)

Tre anrop krävs:

**Anrop 1** – Skapa nyckeln (`postCreateKey`):
```
POST http://ducklake-garage:3903/v2/CreateKey
Authorization: Bearer {GARAGE_ADMIN_TOKEN}
Content-Type: application/json

{ "name": "key-my-bucket" }
```

Svar:
```json
{
  "accessKeyId": "GKxxxxxxxxxxxx",
  "secretAccessKey": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "name": "key-my-bucket"
}
```

> `secretAccessKey` returneras **bara vid skapandet** – sparas inte av Garage efteråt.

**Anrop 2** – Hämta bucket-ID utifrån namn (`getBucketId`):
```
GET http://ducklake-garage:3903/v2/GetBucketInfo?globalAlias={bucketName}
Authorization: Bearer {GARAGE_ADMIN_TOKEN}
```

Svar:
```json
{
  "id": "bucketid...",
  "globalAliases": ["my-bucket"]
}
```

**Anrop 3** – Tilldela behörighet på bucket (`grantBucketPermission`):
```
POST http://ducklake-garage:3903/v2/AllowBucketKey
Authorization: Bearer {GARAGE_ADMIN_TOKEN}
Content-Type: application/json

{
  "bucketId": "bucketid...",
  "accessKeyId": "GKxxxxxxxxxxxx",
  "permissions": {
    "read": true,
    "write": false,
    "owner": false
  }
}
```

> För read/write: sätt `"write": true`

### Steg 1.2 – Ta bort en nyckel (deleteKey) ✅

```
DELETE http://ducklake-garage:3903/v2/DeleteKey?id={keyId}
Authorization: Bearer {GARAGE_ADMIN_TOKEN}
```

### Steg 1.3 – Lista nycklar (listKeys) ✅

```
GET http://ducklake-garage:3903/v2/ListKeys
Authorization: Bearer {GARAGE_ADMIN_TOKEN}
```

Svar: lista av `{ "id": "...", "name": "..." }`.
> `secretAccessKey` ingår inte i listan – nyckeln visas bara vid skapandet.

---

## Fas 2 – PostgresAccessTokenManager

**Fil:** `implementations/PostgresAccessTokenManager.java`

Ansluter till `ducklake-catalog` med admin-kontot via JDBC (`JdbcTemplate`).
Kör rå SQL för att skapa och ta bort användare.

> OBS: använd `jdbcTemplate.execute()` för DDL-satser (CREATE USER, GRANT, DROP USER).

### Steg 2.1 – Skapa read-only-användare (createReadOnlyUser)

```sql
CREATE USER {username} WITH PASSWORD '{password}';
GRANT CONNECT ON DATABASE {database} TO {username};
GRANT USAGE ON SCHEMA public TO {username};
GRANT SELECT ON ALL TABLES IN SCHEMA public TO {username};
```

### Steg 2.2 – Skapa read/write-användare (createReadWriteUser)

```sql
CREATE USER {username} WITH PASSWORD '{password}';
GRANT CONNECT ON DATABASE {database} TO {username};
GRANT USAGE ON SCHEMA public TO {username};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO {username};
```

### Steg 2.3 – Ta bort användare (deleteUser)

```sql
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM {username};
REVOKE ALL ON SCHEMA public FROM {username};
REVOKE CONNECT ON DATABASE {database} FROM {username};
DROP USER {username};
```

### Steg 2.4 – Lista användare (listUsers)

```sql
SELECT usename FROM pg_user WHERE usename LIKE 'dl_%';
```

---

## Fas 3 – Rollhantering i KeyController

**Fil:** `api/KeyController.java`

Lägg till kontroll så att oprivilegierade användare inte kan begära `"readwrite"`.

TODO-kommentaren i `generate()`-metoden markerar var denna kontroll ska läggas in.
Implementeras när autentisering är på plats (Fas 4).

---

## Fas 4 – Autentisering

Lägg till Spring Security för att:
- Identifiera inloggad användare
- Särskilja privilegierade och oprivilegierade användare
- Skydda `/api/keys/generate` med `readwrite`-behörighet

---

## Fas 5 – Driftsättning på cbhcloud

1. Bygg Docker-imagen:
   ```bash
   docker build -t ghcr.io/wildrelation/ducklake-access-manager:latest .
   ```

2. Pusha till GitHub Container Registry:
   ```bash
   docker push ghcr.io/wildrelation/ducklake-access-manager:latest
   ```

3. Skapa ett nytt deployment på cbhcloud:
   - Image: `ghcr.io/wildrelation/ducklake-access-manager:latest`
   - Visibility: **Public**
   - Miljövariabler: se `.env.example`

4. Verifiera att deploymentet kan nå:
   - `ducklake-catalog` internt på port 5432
   - `ducklake-garage` internt på port 3903

---

## Testa lokalt

```bash
# Kopiera och fyll i .env
cp .env.example .env

# Starta (kräver lokal Garage + PostgreSQL eller SSH-tunnel)
mvn spring-boot:run

# Testa key generation
curl -X POST http://localhost:8080/api/keys/generate \
  -H "Content-Type: application/json" \
  -d '{"bucketName": "my-bucket", "permission": "read"}'
```
