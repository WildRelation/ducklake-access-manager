# Implementationsguide – DuckLake Access Manager

Denna guide beskriver steg-för-steg hur tjänsten ska implementeras.
Koden är skriven på engelska; kommentarer och dokumentation på svenska.

---

## Förutsättningar

- Java 17+
- Maven 3.9+
- Tillgång till `ducklake-catalog` (PostgreSQL) och `ducklake-garage` (Garage) på cbhcloud
- Garage Admin Token (finns i `/tmp/garage.toml` i `ducklake-garage`-containern)

---

## Fas 1 – GarageAccessTokenManager ✅ Implementerad

**Fil:** `service/impl/GarageAccessTokenManager.java`

Garage Admin API v2 körs internt på port 3903 i `ducklake-garage`-deploymentet.
Alla anrop autentiseras med en Bearer-token via `Authorization`-headern.

> **OBS:** Garage använder API v2. Endpoints börjar med `/v2/` och använder camelCase-namn.

### Steg 1.1 – Skapa en nyckel (createReadOnlyKey / createReadWriteKey)

Tre anrop krävs:

**Anrop 1** – Skapa nyckeln (`postCreateKey`):
```
POST http://ducklake-garage:3900/v2/CreateKey
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
GET http://ducklake-garage:3900/v2/GetBucketInfo?globalAlias={bucketName}
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
POST http://ducklake-garage:3900/v2/AllowBucketKey
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
DELETE http://ducklake-garage:3900/v2/DeleteKey?id={keyId}
Authorization: Bearer {GARAGE_ADMIN_TOKEN}
```

### Steg 1.3 – Lista nycklar (listKeys) ✅

```
GET http://ducklake-garage:3900/v2/ListKeys
Authorization: Bearer {GARAGE_ADMIN_TOKEN}
```

Svar: lista av `{ "id": "...", "name": "..." }`.
> `secretAccessKey` ingår inte i listan – nyckeln visas bara vid skapandet.

---

## Fas 2 – PostgresAccessTokenManager ✅ Implementerad

**Fil:** `service/impl/PostgresAccessTokenManager.java`

Ansluter till `ducklake-catalog` med admin-kontot via JDBC (`JdbcTemplate`).
Kör rå SQL för att skapa och ta bort användare.

> OBS: DDL-satser (CREATE USER, GRANT, DROP USER) stödjer inte prepared statement-parametrar
> i PostgreSQL. Säkert ändå eftersom användarnamn och lösenord genereras via UUID – ingen
> användarinmatning involverad.

### Steg 2.1 – Skapa read-only-användare ✅

```sql
CREATE USER {username} WITH PASSWORD '{password}';
GRANT CONNECT ON DATABASE {database} TO {username};
GRANT USAGE ON SCHEMA public TO {username};
GRANT SELECT ON ALL TABLES IN SCHEMA public TO {username};
```

### Steg 2.2 – Skapa read/write-användare ✅

```sql
CREATE USER {username} WITH PASSWORD '{password}';
GRANT CONNECT ON DATABASE {database} TO {username};
GRANT USAGE ON SCHEMA public TO {username};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO {username};
```

### Steg 2.3 – Ta bort användare ✅

Inkluderar säkerhetsvalidering: endast användare med prefixet `dl_ro_` eller `dl_rw_`
kan tas bort, för att skydda admin-kontot.

```sql
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM {username};
REVOKE ALL ON SCHEMA public FROM {username};
REVOKE CONNECT ON DATABASE {database} FROM {username};
DROP USER {username};
```

### Steg 2.4 – Lista användare ✅

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

## Fas 5 – Testning ✅ Verifierad

### Alternativ A – Testa lokalt via SSH-tunnlar

Garage är ett publikt deployment men Admin API:n körs på port 3903 som inte är
exponerad utåt. PostgreSQL är privat. Därför behövs två tunnlar.

**Förutsättning:** Java 17+ och Maven installerat lokalt.

**Steg 1 – Öppna två SSH-tunnlar (håll terminalerna öppna)**

Terminal 1 – PostgreSQL (privat deployment):
```bash
ssh -L 5433:localhost:5432 ducklake-catalog@deploy.cloud.cbh.kth.se
```

> ⚠️ Port 5432 kan vara upptagen lokalt (PostgreSQL installerat). Använd därför 5433 i tunneln.

Terminal 2 – Garage Admin API (intern port, ej publik):
```bash
ssh -L 3903:localhost:3903 ducklake-garage@deploy.cloud.cbh.kth.se
```

**Steg 2 – Hämta Garage Admin Token**

SSH:a in i Garage-containern och hämta token:
```bash
ssh ducklake-garage@deploy.cloud.cbh.kth.se
cat /tmp/garage.toml | grep admin_token
```

> ⚠️ Använd alltid `/tmp/garage.toml`, inte `/etc/garage.toml`.
> `/tmp/garage.toml` är den faktiska config som Garage kör med (efter `envsubst`).

**Steg 3 – Konfigurera `.env`**

```bash
cp .env.example .env
```

Fyll i `.env`:
```env
POSTGRES_HOST=127.0.0.1
POSTGRES_PORT=5433
POSTGRES_DB=ducklake
POSTGRES_ADMIN_USER=ducklake
POSTGRES_ADMIN_PASSWORD=cbhcloud

GARAGE_ADMIN_URL=http://127.0.0.1:3903
GARAGE_ADMIN_TOKEN=<token från /tmp/garage.toml>
GARAGE_S3_ENDPOINT=https://ducklake-garage.deploy.cloud.cbh.kth.se

PORT=8080
```

**Steg 4 – Starta tjänsten**

```bash
export $(cat .env | grep -v '^#' | grep -v '^$' | xargs)
mvn spring-boot:run
```

**Steg 5 – Testa med curl**

Skapa en read-only nyckel:
```bash
curl -X POST http://localhost:8080/api/keys/generate \
  -H "Content-Type: application/json" \
  -d '{"bucketName": "ducklake", "permission": "read"}'
```

Förväntat svar:
```json
{
  "s3Key": {
    "keyId": "GKxxxx",
    "secretKey": "xxxx",
    "bucketName": "ducklake",
    "permission": "read",
    "endpoint": "https://..."
  },
  "dbCredentials": {
    "username": "dl_ro_a3f2b1c9",
    "password": "xxxx",
    "host": "127.0.0.1",
    "port": 5432,
    "database": "ducklake",
    "permission": "read"
  },
  "duckdbScript": "INSTALL ducklake; ..."
}
```

Lista alla nycklar:
```bash
curl http://localhost:8080/api/keys
```

Ta bort en nyckel:
```bash
curl -X DELETE "http://localhost:8080/api/keys/{keyId}?pgUsername=dl_ro_a3f2b1c9"
```

---

## Fas 6 – Driftsättning på cbhcloud ✅ Driftsatt

**Produktions-URL:** `https://ducklake-access-manager.app.cloud.cbh.kth.se`

### Portproblem och lösning

cbhcloud:s NetworkPolicy blockerar all inter-deployment-trafik utom på den deklarerade `PORT`.
Garage Admin API körs på port 3903 men bara port 3900 (S3 API) var åtkomlig.

**Lösning:** nginx reverse proxy i `ducklake-garage`-containern:
- nginx lyssnar på port 3900 (den enda öppna porten)
- `/v2/*` → port 3903 (Garage Admin API)
- allt annat → port 3905 (Garage S3 API, omflyttad från 3900)

`GARAGE_ADMIN_URL` i Access Manager pekar därför på port 3900 (inte 3903):
```
GARAGE_ADMIN_URL=http://ducklake-garage:3900
```

### Steg 1 – Bygg och pusha Docker-imagen

```bash
docker build -t ghcr.io/wildrelation/ducklake-access-manager:latest .
docker push ghcr.io/wildrelation/ducklake-access-manager:latest
```

### Steg 2 – Skapa nytt deployment på cbhcloud

| Inställning | Värde |
|---|---|
| Image | `ghcr.io/wildrelation/ducklake-access-manager:latest` |
| Visibility | **Public** |
| PORT | `8080` |
| Health check path | `/healthz` |

### Steg 3 – Miljövariabler

| Variabel | Värde |
|---|---|
| `POSTGRES_HOST` | `ducklake-catalog` |
| `POSTGRES_PORT` | `5432` |
| `POSTGRES_DB` | `ducklake` |
| `POSTGRES_ADMIN_USER` | `ducklake` |
| `POSTGRES_ADMIN_PASSWORD` | `cbhcloud` |
| `GARAGE_ADMIN_URL` | `http://ducklake-garage:3900` |
| `GARAGE_ADMIN_TOKEN` | `<token från /tmp/garage.toml i ducklake-garage>` |
| `GARAGE_S3_ENDPOINT` | `https://ducklake-garage.deploy.cloud.cbh.kth.se` |
| `PORT` | `8080` |

> ⚠️ `GARAGE_ADMIN_URL` pekar på port **3900** (nginx), inte 3903.
> nginx vidarebefordrar `/v2/*`-anrop till Admin API internt.

### Steg 4 – Verifiera i produktion

```bash
curl -X POST https://ducklake-access-manager.app.cloud.cbh.kth.se/api/keys/generate \
  -H "Content-Type: application/json" \
  -d '{"bucketName": "ducklake", "permission": "read"}'
```

Förväntat svar (verifierat 2026-04-29):
```json
{
  "s3Key": {
    "keyId": "GKe53b32dc60dff6053d0133b6",
    "secretKey": "...",
    "bucketName": "ducklake",
    "permission": "read",
    "endpoint": "https://ducklake-garage.cbh.kth.se"
  },
  "dbCredentials": {
    "username": "dl_ro_7df3023f",
    "password": "...",
    "host": "ducklake-catalog",
    "port": 5432,
    "database": "ducklake",
    "permission": "read"
  },
  "duckdbScript": "INSTALL ducklake;\n..."
}
```
