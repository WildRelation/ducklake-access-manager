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

> Imagen innehåller JupyterLab + DuckDB + ducklake-extension förinstallerat.

### 2. Öppna JupyterLab

När deploymentet är igång, klicka **Visit** i cbhcloud — det öppnar JupyterLab i webbläsaren.

### 3. Hämta dina nycklar

Besök `https://ducklake-access-manager.app.cloud.cbh.kth.se/`, generera en nyckel och kopiera DuckDB-scriptet.

### 4. Kör scriptet

Skapa en ny notebook i JupyterLab och kör:

```python
import duckdb

con = duckdb.connect()
con.execute("""
    -- klistra in scriptet härifrån --
    INSTALL ducklake;
    INSTALL postgres;
    LOAD ducklake;
    LOAD postgres;

    CREATE OR REPLACE SECRET ( TYPE postgres, HOST 'ducklake-catalog', ... );
    CREATE OR REPLACE SECRET garage_secret ( TYPE s3, ... );
    ATTACH 'ducklake:postgres:dbname=ducklake' AS my_ducklake (DATA_PATH 's3://ducklake/');
    USE my_ducklake;
""")

# Kör nu queries mot DuckLake
result = con.execute("SELECT * FROM my_table LIMIT 10").fetchdf()
print(result)
```

### Alternativ: SSH + Python (utan Jupyter)

Om du hellre vill köra direkt från terminalen:

```bash
# Anslut till ditt deployment
ssh ditt-deployment@deploy.cloud.cbh.kth.se

# Installera duckdb om det inte redan finns
pip install duckdb

# Kör ett Python-script
python3 - <<'EOF'
import duckdb
con = duckdb.connect()
con.execute("INSTALL ducklake; LOAD ducklake; ...")
EOF
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
    "endpoint": "https://ducklake-garage.deploy.cloud.cbh.kth.se"
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
├── interfaces/
│   ├── ObjectStoreAccessTokenManager.java   # Interface för S3-hantering
│   └── DatabaseAccessTokenManager.java      # Interface för PostgreSQL-hantering
├── implementations/
│   ├── GarageAccessTokenManager.java        # Garage Admin API v2
│   └── PostgresAccessTokenManager.java      # JDBC + DDL SQL
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
ssh -L 5433:localhost:5432 ducklake-catalog@deploy.cloud.cbh.kth.se
ssh -L 3903:localhost:3903 ducklake-garage@deploy.cloud.cbh.kth.se

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
| `GARAGE_S3_ENDPOINT` | `https://ducklake-garage.deploy.cloud.cbh.kth.se` |
| `PORT` | `8080` |

> `GARAGE_ADMIN_URL` pekar på port **3900** (nginx), inte 3903.

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

## Återstående arbete

- **Autentisering (Fas 4)** — KTH Login (OIDC) via Spring Security så att `readwrite` kräver privilegierad användare
