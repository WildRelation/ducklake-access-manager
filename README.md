# DuckLake Access Manager

Tjänst för hantering av datasets, grupper och åtkomstnycklar till DuckLake (PostgreSQL + Garage) på cbhcloud.

Admins skapar datasets, laddar upp data och tilldelar åtkomst till studenter, grupper eller alla inloggade användare. Studenter bläddrar bland tillgängliga datasets och genererar egna credentials via webbgränssnittet — resultatet är ett färdigt DuckDB-script som de kör inifrån ett eget deployment på cbhcloud.

**Produktions-URL:** `https://ducklake-access-manager.app.cloud.cbh.kth.se`

---

## Arkitektur

```
                      ┌─────────────┐
                      │  Keycloak   │   cbhcloud SSO
                      │   (IAM)     │   utfärdar JWT-tokens
                      └──────┬──────┘
                             │ JWT
                             ▼
              ┌──────────────────────────────┐
              │    Access Manager            │
              │    Spring Boot + React       │
              │    :8080                     │
              │                              │
              │  Browse / My Keys / Admin    │
              │  REST API                    │
              └──────┬─────────────┬─────────┘
                     │             │
            ┌────────▼──┐    ┌─────▼──────┐
            │ Postgres  │    │   Garage   │  S3-kompatibel objektlagring
            │ catalog   │    │  (buckets) │
            │           │    │            │
            │ En DB     │    │ En bucket  │
            │ per       │    │ per        │
            │ dataset   │    │ dataset    │
            └────────┬──┘    └─────┬──────┘
                     │             │
                     └──────┬──────┘
                            │
                ┌───────────▼────────────┐
                │  Studentens deployment │
                │  (DuckDB / JupyterLab) │
                │  Kör queries härifrån  │
                └────────────────────────┘
```

Tjänsten kommunicerar med:
- `ducklake-catalog:5432` — PostgreSQL via JDBC (admin-anslutning)
- `ducklake-garage:3900` — Garage Admin API v2 via nginx

Garage Admin API körs internt på port 3903 men är inte åtkomlig direkt mellan deployments (NetworkPolicy). En nginx reverse proxy i `ducklake-garage`-containern vidarebefordrar `/v2/*` från port 3900 till 3903.

---

## Koncept

### Dataset
Det centrala begreppet. Tre delar hänger ihop under ett bucket-namn:
- **Garage-bucket** — lagrar Parquet-filerna (DuckLake-data)
- **Postgres-databas** — `dl_<bucket_med_understreck>`, håller DuckLakes katalogtabeller för just detta dataset. En student med access till dataset A kan bokstavligen inte läsa dataset B:s katalog — det finns inget CONNECT-privilegium.
- **Metadatarad** i tabellen `datasets` — titel, beskrivning, ägare, synlighet

### Synlighet
- `public` — alla inloggade användare kan se och läsa
- `private` — kräver explicit grant (user, grupp eller everyone)

### Grant
Tre typer:
- `user` — specifik e-postadress
- `group` — alla i en namngiven grupp
- `everyone` — alla inloggade användare (kortväg för public-liknande access med möjlighet att återkalla)

### Nyckel
När en student genererar nycklar skapas automatiskt:
- En S3-nyckel i Garage (read eller readwrite på bucketen)
- En PostgreSQL-användare i datasetets egna databas (SELECT, eller full DML för readwrite)
- Ett färdigt DuckDB-script som kopplar ihop allt

---

## Hur studenter använder tjänsten

1. Logga in på `https://ducklake-access-manager.app.cloud.cbh.kth.se/`
2. Bläddra bland datasets i **Browse** — publika och tilldelade private datasets syns
3. Klicka **Generate Keys** på ett dataset — en modal visas med credentials som bara visas en gång:
   - **DuckDB-script** — klistra in direkt i DuckDB/JupyterLab
   - **Download .env** — laddar ned en `.env.<bucket>-fil` med standardiserade miljövariabelnamn
4. Kör scriptet **inifrån ett eget deployment på cbhcloud** (inte lokalt — `ducklake-catalog` och `ducklake-garage` är interna Kubernetes-services)

```python
import duckdb
con = duckdb.connect()
con.execute(generated_script)   # klistrar in scriptet från access manager
df = con.execute("SELECT * FROM passengers LIMIT 10").fetchdf()
```

`.env`-filen kan laddas ned direkt från nyckeldialogen och innehåller:

```bash
# PostgreSQL — psycopg2, SQLAlchemy, libpq
PGHOST=ducklake-catalog
PGPORT=5432
PGDATABASE=dl_titanic_2026
PGUSER=dl_ro_xxxxxxxx
PGPASSWORD=...

# S3 — boto3, s3fs, DuckDB
AWS_ACCESS_KEY_ID=GKxxxxxxxx
AWS_SECRET_ACCESS_KEY=...
AWS_DEFAULT_REGION=garage
AWS_ENDPOINT_URL=http://ducklake-garage:3900
S3_BUCKET=titanic-2026
```

Ladda den i Python med `python-dotenv`:
```python
from dotenv import load_dotenv
load_dotenv(".env.titanic-2026")
import os, boto3, psycopg2
```

> ⚠️ Scriptet och `.env`-filen körs INTE lokalt — de fungerar bara inifrån ett deployment på kthcloud där `ducklake-catalog` och `ducklake-garage` är nåbara.

---

## Student deployment guide

### 1. Skapa ett nytt deployment på cbhcloud

Gå till [cloud.cbh.kth.se](https://cloud.cbh.kth.se) och skapa ett nytt deployment:

| Fält | Värde |
|---|---|
| Image | Valfri JupyterLab-image (se nedan) |
| PORT | `8888` |
| Visibility | `Public` |
| Health check | `/lab` |

Bygg en egen JupyterLab-image:

```dockerfile
FROM quay.io/jupyter/base-notebook:latest
RUN pip install duckdb jupyterlab
```

```bash
docker build -t ghcr.io/<ditt-användarnamn>/ducklake-student:latest .
docker push ghcr.io/<ditt-användarnamn>/ducklake-student:latest
```

**cbhcloud-specifika krav:**
- **PORT måste vara `8888`** — JupyterLab lyssnar på 8888
- **Health check måste vara `/lab`** (gemener) — returnerar HTTP 200
- **`--allow-root` krävs** — cbhcloud kör containrar som root

### 2. Kör DuckDB-scriptet

Skapa en ny notebook och kör varje sats separat (DuckDB accepterar en sats per `execute()`):

```python
import duckdb
con = duckdb.connect()
con.execute("INSTALL ducklake")
con.execute("INSTALL postgres")
con.execute("LOAD ducklake")
con.execute("LOAD postgres")

# Klistra in resten av det genererade scriptet här
# ...

print(con.execute("SHOW ALL TABLES").df())
```

---

## Gränssnitt

### Webb-UI

| Vy | Vem ser den | Innehåll |
|---|---|---|
| **Browse** | Alla | Publika datasets + private man har access till. Sök, filtrera, generera nycklar. |
| **My Keys** | Alla | Egna aktiva nycklar. Admin ser alla med Created By-kolumn. |
| **Admin → Datasets** | Admin | Skapa/uppdatera/ta bort datasets (bucket + Postgres-DB skapas atomärt). |
| **Admin → Groups** | Admin | Skapa grupper, lägg till/ta bort enskilda medlemmar, eller massimportera via textarea (klistra in e-postlista). |
| **Admin → Grants** | Admin | Tilldela access: user (e-post), group (dropdown) eller @everyone. |

### REST API

Alla endpoints kräver `Authorization: Bearer <token>` om inget annat anges.

#### Konfiguration (publik)
```
GET /api/config          → {keycloakBase, clientId}   (ingen auth)
GET /healthz             → 200 OK                      (ingen auth)
```

#### Datasets
```
GET    /api/datasets              Lista synliga datasets (admin: alla, annars public + granted)
GET    /api/datasets/{bucket}     Hämta ett dataset
POST   /api/datasets              Skapa dataset (admin) — body: {bucketName, title, description, visibility}
PATCH  /api/datasets/{bucket}     Uppdatera metadata (admin eller ägare)
DELETE /api/datasets/{bucket}     Ta bort dataset (admin eller ägare, bucket måste vara tom)
```

#### Grupper (admin)
```
GET    /api/groups                Lista alla grupper (med medlemmar)
GET    /api/groups/{name}         Hämta en grupp med medlemslista
POST   /api/groups                Skapa grupp — body: {name, description}
DELETE /api/groups/{name}         Ta bort grupp
POST   /api/groups/{name}/members      Lägg till medlem — body: {email}
POST   /api/groups/{name}/members/bulk Massimportera — body: {text: "a@b.com\nc@d.com"} eller {emails: [...]}
                                         Returnerar: {added, skipped, invalid}
DELETE /api/groups/{name}/members      Ta bort medlem — body: {email}
```

#### Grants (admin)
```
GET    /api/admin/grants          Lista alla grants
POST   /api/admin/grants          Tilldela access:
                                    {principalType, principalId, bucketName}
                                    principalType: "user" | "group" | "everyone"
                                    principalId: e-post / gruppnamn (ej relevant för everyone)
                                  Bakåtkompatibelt: {studentEmail, bucketName} → user-grant
DELETE /api/admin/grants          Återkalla access (samma body-format som POST)
```

#### Nycklar
```
POST   /api/keys/generate         Generera nyckel — body: {bucketName, permission}
                                    permission: "read" (default) eller "readwrite" (kräver admin)
                                    Svar: {s3Key, dbCredentials, duckdbScript, envFile}
                                      s3Key:         {keyId, secretKey, bucketName, permission, endpoint, region}
                                      dbCredentials: {host, port, database, username, password}
                                      duckdbScript:  färdigt SQL-script att köra i DuckDB
                                      envFile:       .env-fil med PGHOST/AWS_*-variabler (standard library-format)
GET    /api/keys                  Lista nycklar (admin: alla, annars egna)
DELETE /api/keys/{keyId}          Ta bort nyckel — ?pgUsername=dl_ro_xxxxxxxx (valfri)
```

#### Buckets (admin, Garage-vy)
```
GET    /api/admin/buckets         Lista alla Garage-buckets
POST   /api/admin/buckets         Skapa bucket — body: {name}
DELETE /api/admin/buckets/{name}  Ta bort bucket (409 om inte tom)
GET    /api/buckets               Lista buckets synliga för anroparen
```

---

## Behörigheter

| Behörighet | Garage (S3) | PostgreSQL |
|---|---|---|
| `read` | GET på bucket | SELECT på alla tabeller |
| `readwrite` | GET + PUT + DELETE | SELECT, INSERT, UPDATE, DELETE + CREATE schema |

PostgreSQL-användare skapas med prefix `dl_ro_` (read) eller `dl_rw_` (readwrite).

### Åtkomstregler

| Endpoint | Student | Admin |
|---|---|---|
| `GET /api/datasets` | Public + granted datasets | Alla datasets |
| `POST /api/keys/generate` (read) | ✅ (kräver dataset public eller grant) | ✅ |
| `POST /api/keys/generate` (readwrite) | ❌ 403 | ✅ |
| `GET /api/keys` | Bara egna nycklar | Alla nycklar |
| `DELETE /api/keys/{keyId}` | Bara egna nycklar | Alla nycklar |
| `/api/admin/**` | ❌ 403 | ✅ |
| `/api/groups/**` | ❌ 403 | ✅ |
| `/api/datasets/**` (skriva) | Bara egna datasets | ✅ |

---

## Kodstruktur

```
src/main/java/com/ducklake/accessmanager/
├── api/
│   ├── AdminController.java          # /api/admin/** (buckets + generaliserade grants)
│   ├── BucketController.java         # /api/buckets (bucket-lista per användare)
│   ├── ConfigController.java         # /api/config (publik Keycloak-konfiguration)
│   ├── DatasetController.java        # /api/datasets/** (dataset CRUD)
│   ├── GroupController.java          # /api/groups/** (grupp CRUD + medlemmar)
│   ├── HealthController.java         # /healthz
│   └── KeyController.java            # /api/keys (generera, lista, ta bort nycklar)
├── config/
│   └── SecurityConfig.java           # OAuth2 JWT-validering, isAdmin(), endpoint-skydd
├── service/
│   ├── DatabaseAccessTokenManager.java      # Interface: createReadOnlyUser(db), createReadWriteUser(db), deleteUser(user, db)
│   ├── KeyMappingService.java               # Interface: saveMapping, findDatabase, findOwner, ...
│   ├── ObjectStoreAccessTokenManager.java   # Interface: listBuckets, createBucket, createKey, ...
│   └── impl/
│       ├── AccessService.java               # dataset_grants: user/group/everyone + migration från student_grants
│       ├── DatasetService.java              # Dataset CRUD + atomär bucket+DB-livscykel + startup-sync
│       ├── GarageAccessTokenManager.java    # Garage Admin API v2 (HTTP mot port 3900)
│       ├── GroupService.java                # groups + group_members CRUD
│       ├── PostgresAccessTokenManager.java  # JDBC: skapar dl_ro_/dl_rw_-användare per dataset-DB
│       ├── PostgresAdminOps.java            # CREATE/DROP DATABASE, jdbcFor(db) för in-database grants
│       └── PostgresKeyMappingService.java   # key_user_mapping-tabell i PostgreSQL
├── infrastructure/
│   └── garage/
│       ├── GarageBucketResponse.java
│       ├── GarageKeyListItem.java
│       └── GarageKeyResponse.java
└── model/
    ├── AccessKey.java
    ├── Bucket.java
    ├── BucketGrant.java
    ├── Dataset.java
    ├── DbCredentials.java
    ├── GeneratedCredentials.java
    ├── Grant.java
    ├── Group.java
    ├── KeyListItem.java
    └── KeyRequest.java

src/main/resources/
├── static/index.html        # Webb-UI (React + Babel standalone, ingen byggprocess)
└── application.properties
```

---

## Databasschema

Tabellerna skapas automatiskt vid uppstart via `CREATE TABLE IF NOT EXISTS`.

```sql
-- Ägarskapsregister: kopplar Garage-nyckel till Keycloak-användare och dataset-DB
key_user_mapping (
    garage_key_id  VARCHAR PRIMARY KEY,
    keycloak_sub   VARCHAR NOT NULL,
    display_name   VARCHAR,
    created_at     TIMESTAMP DEFAULT NOW(),
    pg_database    VARCHAR    -- vilken dataset-DB nyckeln tillhör (null = legacy)
)

-- Dataset-metadata
datasets (
    bucket_name   VARCHAR PRIMARY KEY,
    pg_database   VARCHAR NOT NULL UNIQUE,   -- t.ex. dl_titanic_2026
    title         VARCHAR NOT NULL,
    description   TEXT,
    owner_email   VARCHAR NOT NULL,
    visibility    VARCHAR NOT NULL DEFAULT 'private'
                  CHECK (visibility IN ('private','public')),
    created_at    TIMESTAMP DEFAULT NOW()
)

-- Grupper
groups (
    name        VARCHAR PRIMARY KEY,
    description VARCHAR,
    created_by  VARCHAR,
    created_at  TIMESTAMP DEFAULT NOW()
)

-- Gruppmedlemmar
group_members (
    group_name   VARCHAR NOT NULL REFERENCES groups(name) ON DELETE CASCADE,
    member_email VARCHAR NOT NULL,
    added_at     TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (group_name, member_email)
)

-- Generaliserade grants (ersätter student_grants)
dataset_grants (
    bucket_name    VARCHAR NOT NULL,
    principal_type VARCHAR NOT NULL CHECK (principal_type IN ('user', 'group', 'everyone')),
    principal_id   VARCHAR NOT NULL,   -- e-post / gruppnamn / '*' för everyone
    granted_at     TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (bucket_name, principal_type, principal_id)
)

-- Legacy (kvar för rollback-möjlighet, migreras automatiskt till dataset_grants)
student_grants (student_email, bucket_name, granted_at)
```

Bucket-listan hämtas direkt från Garage — det finns ingen separat bucket-tabell i PostgreSQL.

---

## Autentisering

### Inloggningsflöde (OAuth2 Authorization Code + PKCE)

1. Användaren klickar **Sign in with KTH**
2. Webbläsaren dirigeras till Keycloak
3. Efter inloggning byts `code` mot ett access token
4. Tokenet lagras i `sessionStorage` och skickas med alla API-anrop

**Keycloak-klient:**
- Realm: `cloud`
- Client ID: `ducklake`
- Public client (inget client_secret)

### Admin-roll

Avgörs av JWT-claimet `resource_access.ducklake.roles` — om listan innehåller `"admin"` behandlas användaren som admin. Det är en client role på `ducklake`-klienten i Keycloak.

---

## Driftsättning på cbhcloud

```bash
# Använd alltid ny versionstagg — överskrivning av befintlig tag triggar
# inte ny pull om noden har den cachad (imagePullPolicy: IfNotPresent)
docker build --network=host -t ghcr.io/wildrelation/ducklake-access-manager:v12 .
docker push ghcr.io/wildrelation/ducklake-access-manager:v12
```

Uppdatera sedan image-taggen i cbhcloud-deploymentet.

**Miljövariabler:**

| Variabel | Värde |
|---|---|
| `POSTGRES_HOST` | `ducklake-catalog` |
| `POSTGRES_DB` | `ducklake` |
| `POSTGRES_ADMIN_USER` | `ducklake` |
| `POSTGRES_ADMIN_PASSWORD` | `cbhcloud` |
| `POSTGRES_PUBLIC_HOST` | Publikt hostname för PostgreSQL i det genererade DuckDB-scriptet (valfri, standard: `POSTGRES_HOST`) |
| `GARAGE_ADMIN_URL` | `http://ducklake-garage:3900` |
| `GARAGE_ADMIN_TOKEN` | Token från `/tmp/garage.toml` i ducklake-garage |
| `GARAGE_S3_ENDPOINT` | `ducklake-garage:3900` |
| `GARAGE_S3_REGION` | `garage` |
| `KEYCLOAK_ISSUER_URI` | `https://iam.cloud.cbh.kth.se/realms/cloud` (valfri, detta är standard) |
| `PORT` | `8080` |

> `POSTGRES_PORT` är hårdkodad till `5432` — sätt den inte som miljövariabel (Kubernetes injicerar `POSTGRES_PORT=tcp://...` för services med samma namn, vilket korrumperar värdet).

**Valfria miljövariabler för frontend-konfiguration:**

| Variabel | Standardvärde |
|---|---|
| `DUCKLAKE_FRONTEND_KEYCLOAK_BASE` | `https://iam.cloud.cbh.kth.se/realms/cloud/protocol/openid-connect` |
| `DUCKLAKE_FRONTEND_CLIENT_ID` | `ducklake` |

---

## Datamigrering vid uppgradering

Appen hanterar alla schemaändringar automatiskt vid uppstart — inga manuella SQL-kommandon krävs. Vid uppgradering från v1 sker följande automatiskt:

1. `pg_database`-kolumn läggs till på `key_user_mapping`
2. Tabellerna `groups`, `group_members`, `dataset_grants`, `datasets` skapas
3. Befintliga rader i `student_grants` migreras till `dataset_grants` (som user-grants)
4. Befintliga Garage-buckets registreras automatiskt som datasets med `visibility=public`

`migrate.sql` i repots rot innehåller samma SQL och kan köras manuellt om så önskas.

---

## Lokal testning

Öppna SSH-tunnlar i separata terminaler:

```bash
# OBS: port 3900 (nginx), inte 3903 — 3903 är blockerad av NetworkPolicy
ssh -L 5433:localhost:5432 ducklake-catalog@deploy.cloud.cbh.kth.se
ssh -L 3900:localhost:3900 ducklake-garage@deploy.cloud.cbh.kth.se
```

Konfigurera miljövariabler och starta:

```bash
cp .env.example .env   # fyll i värden
export $(cat .env | grep -v '^#' | grep -v '^$' | xargs)
mvn spring-boot:run
```

---

## Teknisk stack

| Del | Teknologi |
|---|---|
| Backend | Java 17 + Spring Boot 3.2.5 |
| Autentisering | Spring Security + OAuth2 Resource Server (JWT/JWKS) |
| PostgreSQL | JdbcTemplate (DDL direkt, ingen ORM) |
| Garage | REST mot Admin API v2 |
| Frontend | React + Babel standalone (ingen byggprocess) |
| Containerisering | Docker, ghcr.io |

---

## Kända problem och lösningar

### S3-fel vid körning av DuckDB-script

**Symptom:** `AuthorizationHeaderMalformed` eller signaturfel.

**Orsak:** Fel värden på `GARAGE_S3_ENDPOINT` eller `GARAGE_S3_REGION`.

**Lösning:** Sätt rätt miljövariabler i access manager-deploymentet:
```
GARAGE_S3_ENDPOINT = ducklake-garage:3900    ← host:port, inget protokoll
GARAGE_S3_REGION   = garage                  ← måste matcha s3_region i garage.toml
```

---

### 403 Invalid signature från Garage (nginx Host-header)

**Symptom:** `AccessDenied: Forbidden: Invalid signature` trots rätt credentials.

**Orsak:** nginx skickar `Host: ducklake-garage` utan port. S3 signature v4 inkluderar `Host`-headern i signaturen — klienten signerar med port men Garage tar emot utan → mismatch.

**Lösning:** `proxy_set_header Host $http_host` i nginx.conf. Dokumenterat i [`garage-cbhcloud-quickstart`](https://github.com/WildRelation/garage-cbhcloud-quickstart).

---

### Deployment kör gammal kod trots ny image pushad

**Orsak:** Kubernetes standard-`imagePullPolicy` är `IfNotPresent` — om imagen är cachad på noden dras den inte om.

**Lösning:** Använd alltid ny versionstagg (`:v12`, `:v13` osv.) istället för att överskriva befintlig.

---

### DuckDB ATTACH säger "relation … does not exist"

**Symptom:** Read-only nyckel misslyckas med att se tabeller.

**Orsak:** En read-only nyckel användes innan någon writer kört `ATTACH` på datasetet — DuckLakes katalogtabeller skapas lazily vid första write-attach.

**Lösning:** Generera en readwrite-nyckel som admin, kör `ATTACH` en gång (t.ex. `CREATE TABLE _bootstrap AS SELECT 1`), sedan fungerar read-nycklar.

---

## Återstående arbete

- **Java-tutorial** — lägg till ett avsnitt i Student deployment guide som visar anslutning från Java (AWS SDK v2 för S3, JDBC för PostgreSQL)
