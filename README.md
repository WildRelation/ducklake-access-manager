# DuckLake Access Manager

Tjänst för automatisk generering och hantering av åtkomstnycklar till DuckLake (PostgreSQL + Garage) på cbhcloud.

Istället för att dela ut credentials manuellt kan användare besöka webbgränssnittet och få ett färdigt DuckDB-anslutningsscript på några sekunder.

**Produktions-URL:** `https://ducklake-access-manager.app.cloud.cbh.kth.se`

---

## Hur studenter använder tjänsten

```
┌─────────────────────────────────────────────────────────────────┐
│                        cbhcloud cluster                         │
│                                                                 │
│  ┌──────────────────────┐        ┌──────────────────────────┐   │
│  │  ducklake-access-    │        │   Studentens deployment  │   │
│  │  manager             │        │   (Jupyter / Python)     │   │
│  │                      │        │                          │   │
│  │  1. Student besöker  │        │  3. Student kör          │   │
│  │     webbgränssnittet │        │     DuckDB-scriptet här  │   │
│  │                      │        │          │               │   │
│  │  2. Kopierar scriptet│        │          ▼               │   │
│  │     med nycklarna    │        │   ducklake-catalog:5432  │   │
│  └──────────────────────┘        │   ducklake-garage:3900   │   │
│                                  └──────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────┐   ┌──────────────────────────────┐     │
│  │  ducklake-catalog   │   │  ducklake-garage             │     │
│  │  (PostgreSQL)       │   │  (S3 / Garage)               │     │
│  └─────────────────────┘   └──────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────┘

     Lokal dator
  ┌──────────────┐
  │  Webbläsare  │──── besöker access manager UI ────▶ (steg 1–2)
  └──────────────┘

  ⚠️  DuckDB-scriptet körs INTE lokalt — det körs från ett
      deployment på kthcloud, där ducklake-catalog är nåbar.
```

**Steg för steg:**

1. Besök `https://ducklake-access-manager.app.cloud.cbh.kth.se/` i webbläsaren
2. Välj bucket och behörighet → klicka **Generate Key** → kopiera DuckDB-scriptet
3. Skapa ett eget deployment på kthcloud (se [Student deployment guide](#student-deployment-guide) nedan)
4. Kör DuckDB-scriptet **inifrån det deploymentet** — inte lokalt på din dator

Hostname `ducklake-catalog` är bara nåbar inom cbhcloud-clustret.

---

## Student deployment guide

Så här skapar du ett eget Python/Jupyter-deployment på cbhcloud där du kan köra DuckDB-scriptet.

### 1. Skapa ett nytt deployment på cbhcloud

Gå till [cloud.cbh.kth.se](https://cloud.cbh.kth.se) och skapa ett nytt deployment med följande inställningar:

| Fält | Värde |
|---|---|
| Image tag | `ghcr.io/wildrelation/ducklake-student:latest` |
| PORT | `8888` |
| Visibility | `Public` |
| Health check | `/lab` |

> Imagen innehåller JupyterLab + DuckDB + ducklake- och postgres-extensions förinstallerat.

**Viktigt — cbhcloud-specifika krav:**

- **PORT måste vara `8888`** — cbhcloud skickar trafik till porten i PORT-variabeln. JupyterLab lyssnar på 8888; fel port ger 503.
- **Health check måste vara `/lab`** (gemener) — cbhcloud kräver att sökvägen returnerar HTTP 200 för att markera deploymentet som friskt. `/lab` fungerar; `/healthz` eller `/Lab` ger 404.
- **`--allow-root` krävs** — cbhcloud kör containrar som root. JupyterLab 4.x vägrar starta som root utan denna flagg, vilket också ger 503 trots att containern är "Running".

### 2. Öppna JupyterLab

När deploymentet är igång, klicka **Visit** i cbhcloud — det öppnar JupyterLab i webbläsaren.

### 3. Hämta dina nycklar

Besök `https://ducklake-access-manager.app.cloud.cbh.kth.se/`, generera en nyckel och kopiera DuckDB-scriptet.

### 4. Kör scriptet

Skapa en ny notebook i JupyterLab och kör följande i en cell. Varje `con.execute()` måste vara ett eget anrop — DuckDB:s Python-API accepterar bara en SQL-sats per `execute()`-anrop.

```python
import duckdb

con = duckdb.connect()

# Installera och ladda extensions
con.execute("INSTALL ducklake")
con.execute("INSTALL postgres")
con.execute("LOAD ducklake")
con.execute("LOAD postgres")

# PostgreSQL-secret (ersätt med dina värden från access manager)
con.execute("""
    CREATE OR REPLACE SECRET (
        TYPE postgres,
        HOST 'ducklake-catalog',
        PORT 5432,
        DATABASE ducklake,
        USER 'dl_ro_xxxxxxxx',
        PASSWORD 'ditt-lösenord'
    )
""")

# Garage S3-secret
con.execute("""
    CREATE OR REPLACE SECRET garage_secret (
        TYPE s3,
        PROVIDER config,
        KEY_ID 'GKxxxxxxxx',
        SECRET 'din-secret',
        REGION 'garage',
        ENDPOINT 'ducklake-garage:3900',
        URL_STYLE 'path',
        USE_SSL false
    )
""")

# Anslut till DuckLake
con.execute("""
    ATTACH 'ducklake:postgres:dbname=ducklake' AS my_ducklake (
        DATA_PATH 's3://ducklake/'
    )
""")

# Kör queries
print(con.execute("SHOW ALL TABLES").df())
print(con.execute("SELECT * FROM my_ducklake.titanic LIMIT 10").df())
```

---

## Vad tjänsten gör

När en användare begär en nyckel sker tre saker automatiskt:

1. En S3-nyckel skapas i Garage med rätt behörighet på bucketen
2. En PostgreSQL-användare skapas med rätt behörighet på databasen
3. Ett färdigt DuckDB-script returneras — kopiera och kör inifrån ett kthcloud-deployment

```json
{
  "s3Key": {
    "keyId": "GKxxxxxxxxxxxx",
    "secretKey": "...",
    "bucketName": "ducklake",
    "permission": "read",
    "endpoint": "ducklake-garage:3900",
    "region": "garage"
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

---

## Arkitektur

```
ducklake-access-manager  →  ducklake-garage:3900/v2/*  (Garage Admin API via nginx)
ducklake-access-manager  →  ducklake-catalog:5432      (PostgreSQL via JDBC)
```

Garage Admin API körs internt på port 3903 men är inte åtkomlig direkt mellan deployments på cbhcloud (NetworkPolicy). En nginx reverse proxy i `ducklake-garage`-containern tar emot trafik på port 3900 och vidarebefordrar `/v2/*` till port 3903.

Se [`garage-cbhcloud-quickstart`](https://github.com/WildRelation/garage-cbhcloud-quickstart) för detaljer om Garage-deploymentet.

---

## Gränssnitt

### Webb-UI

Öppna `https://ducklake-access-manager.app.cloud.cbh.kth.se/` i webbläsaren.

- Välj bucket och behörighet (read / readwrite)
- Klicka **Generera nyckel**
- Kopiera DuckDB-scriptet från modalen

### REST API

**Generera nyckel**
```
POST /api/keys/generate
Content-Type: application/json

{"bucketName": "ducklake", "permission": "read"}
```

**Lista nycklar**
```
GET /api/keys
```

**Ta bort nyckel**
```
DELETE /api/keys/{keyId}?pgUsername=dl_ro_xxxxxxxx
```

**Hälsokontroll**
```
GET /healthz  →  200 OK
```

---

## Behörigheter

| Behörighet | Garage (S3) | PostgreSQL |
|---|---|---|
| `read` | GET på bucket | SELECT |
| `readwrite` | GET + PUT + DELETE | SELECT, INSERT, UPDATE, DELETE |

PostgreSQL-användare skapas med prefix `dl_ro_` (read) eller `dl_rw_` (readwrite).
Endast användare med dessa prefix kan tas bort — admin-kontot skyddas.

---

## Kodstruktur

```
src/main/java/com/ducklake/accessmanager/
├── service/
│   ├── ObjectStoreAccessTokenManager.java   # Interface för S3-hantering
│   ├── DatabaseAccessTokenManager.java      # Interface för PostgreSQL-hantering
│   └── impl/
│       ├── GarageAccessTokenManager.java    # Garage Admin API v2
│       └── PostgresAccessTokenManager.java  # JDBC + DDL SQL
├── infrastructure/
│   └── garage/
│       ├── GarageBucketResponse.java        # DTO för GetBucketInfo
│       ├── GarageKeyListItem.java           # DTO för ListKeys
│       └── GarageKeyResponse.java           # DTO för CreateKey
├── api/
│   ├── KeyController.java                   # REST-endpoints
│   └── HealthController.java                # /healthz
└── model/
    ├── AccessKey.java
    ├── DbCredentials.java
    ├── GeneratedCredentials.java
    └── KeyRequest.java

src/main/resources/
├── static/index.html                        # Webb-UI
└── application.properties
```

---

## Lokal testning

Se [IMPLEMENTATION.md](IMPLEMENTATION.md) Fas 5 för fullständiga instruktioner med SSH-tunnlar.

Snabbstart:
```bash
# Öppna tunnlar i separata terminaler
# OBS: tunnel mot port 3900 (nginx), inte 3903 — 3903 är blockerad av NetworkPolicy
ssh -L 5433:localhost:5432 ducklake-catalog@deploy.cloud.cbh.kth.se
ssh -L 3900:localhost:3900 ducklake-garage@deploy.cloud.cbh.kth.se

# Konfigurera miljövariabler
cp .env.example .env  # fyll i värden

# Starta
export $(cat .env | grep -v '^#' | grep -v '^$' | xargs)
mvn spring-boot:run
```

---

## Driftsättning på cbhcloud

```bash
docker build -t ghcr.io/wildrelation/ducklake-access-manager:latest .
docker push ghcr.io/wildrelation/ducklake-access-manager:latest
```

**Miljövariabler:**

| Variabel | Värde |
|---|---|
| `POSTGRES_HOST` | `ducklake-catalog` |
| `POSTGRES_PORT` | `5432` |
| `POSTGRES_DB` | `ducklake` |
| `POSTGRES_ADMIN_USER` | `ducklake` |
| `POSTGRES_ADMIN_PASSWORD` | `cbhcloud` |
| `GARAGE_ADMIN_URL` | `http://ducklake-garage:3900` |
| `GARAGE_ADMIN_TOKEN` | token från `/tmp/garage.toml` i ducklake-garage |
| `GARAGE_S3_ENDPOINT` | `ducklake-garage:3900` |
| `GARAGE_S3_REGION` | `garage` |
| `PORT` | `8080` |

> `GARAGE_ADMIN_URL` pekar på port **3900** (nginx), inte 3903.
> `GARAGE_S3_ENDPOINT` ska vara `host:port` utan protokoll — DuckDB lägger till http/https baserat på `USE_SSL`.
> `GARAGE_S3_REGION` måste matcha `s3_region` i `garage.toml` (standard: `garage`).

---

## Teknisk stack

| Del | Teknologi |
|---|---|
| Backend | Java 17 + Spring Boot 3.2.5 |
| PostgreSQL | JdbcTemplate (DDL direkt) |
| Garage | REST mot Admin API v2 |
| Frontend | Vanilla HTML/CSS/JS (ingen byggprocess) |
| Containerisering | Docker, ghcr.io |

---

## Kända problem och lösningar

### S3-fel vid körning av DuckDB-script inifrån kthcloud

**Symptom:** `AuthorizationHeaderMalformed: unexpected scope: .../local/s3/aws4_request` eller liknande signaturfel när man kör `SELECT` mot data i Garage.

**Orsak:** Tre separata konfigurationsfel i det genererade scriptet:

| Fel | Förklaring |
|---|---|
| `REGION 'local'` | Måste matcha `s3_region` i `garage.toml` — för denna instans är det `'garage'` |
| `ENDPOINT 'https://...'` | DuckDB accepterar inte protokoll i `ENDPOINT` — ska vara `host:port` utan `https://` |
| Publikt hostname | Det publika DNS-namnet för Garage löser inte upp inifrån kthcloud-clustret |

**Lösning:** Sätt miljövariablerna i `ducklake-access-manager`-deploymentet korrekt:

```
GARAGE_S3_ENDPOINT = ducklake-garage:3900    ← host:port, inget protokoll, internt namn
GARAGE_S3_REGION   = garage                  ← måste matcha s3_region i garage.toml
```

Det genererade scriptet ser då ut så här (korrekt):

```sql
CREATE OR REPLACE SECRET garage_secret (
    TYPE s3,
    PROVIDER config,
    KEY_ID 'GKxxxxxxxx',
    SECRET '...',
    REGION 'garage',           -- matchar s3_region i garage.toml
    ENDPOINT 'ducklake-garage:3900',  -- internt hostname, inget protokoll
    URL_STYLE 'path',
    USE_SSL false
);
```

**Varför fungerar det bara inifrån kthcloud?** `ducklake-garage` är ett internt Kubernetes-servicename som bara löser upp inom cbhcloud-clustret. Scriptet är avsett att köras från ett eget deployment på kthcloud (t.ex. JupyterLab), inte lokalt.

### 403 Invalid signature från Garage (nginx Host-header)

**Symptom:** `AccessDenied: Forbidden: Invalid signature` trots rätt KEY_ID, SECRET, region och endpoint. Reproducerbart med boto3.

**Orsak:** nginx:s standardvärde `proxy_set_header Host $host` skickar bara hostname utan port (`ducklake-garage`). S3 signature v4 inkluderar `Host`-headern i det signerade strängen. Klienten signerar med `Host: ducklake-garage:3900` (med port) men Garage tar emot `Host: ducklake-garage` (utan port) → signaturen matchar inte → 403.

**Lösning:** `proxy_set_header Host $http_host` i nginx.conf — bevarar hela headern inklusive port. Dokumenterat i [`garage-cbhcloud-quickstart`](https://github.com/WildRelation/garage-cbhcloud-quickstart).

---

## Återstående arbete

- **Autentisering (Fas 4)** — KTH Login (OIDC) via Spring Security så att `readwrite` kräver privilegierad användare
