# DuckLake Access Manager – Projektplan

Tjänst för automatisk generering och hantering av åtkomstnycklar till DuckLake (PostgreSQL + Garage) på cbhcloud.

---

## Nuläge

Tre befintliga repositories utgör grunden:

| Repository | Syfte |
|---|---|
| `garage-cbhcloud-quickstart` | Guide för att driftsätta Garage på cbhcloud |
| `ducklake-guide-garage` | Tutorial: PostgreSQL + Garage + DuckDB via SSH-tunnel |
| `ducklake-connect` | Python-klient som ansluter till DuckLake |

**Problemet:** Åtkomstnycklar genereras och delas ut manuellt. Det ska automatiseras.

---

## Övergripande arkitektur

```mermaid
graph TB
    subgraph cbhcloud
        subgraph "Befintliga deployments"
            PG["ducklake-catalog\nPostgreSQL :5432\n(Private)"]
            GR["ducklake-garage\nGarage S3 :3900\n(Public)"]
            GRA["Garage Admin API\n:3903\n(Intern)"]
        end

        subgraph "Ny tjänst – Access Manager"
            API["Access Manager API"]
            UI["Web UI"]
            API --> UI
        end

        API -->|"Skapa PostgreSQL-användare\nvia JDBC"| PG
        API -->|"Skapa S3-nycklar\nvia Admin API"| GRA
        GRA -.->|"tillhör"| GR
    end

    Student["Student / Användare"] -->|"Begär åtkomst"| UI
    UI -->|"Returnerar DuckDB-script\n+ råa nycklar"| Student
```

---

## Komponentdesign

```mermaid
classDiagram
    class ObjectStoreAccessTokenManager {
        <<interface>>
        +createReadOnlyKey(bucketName: String) AccessKey
        +createReadWriteKey(bucketName: String) AccessKey
        +deleteKey(keyId: String)
        +listKeys() List~AccessKey~
    }

    class DatabaseAccessTokenManager {
        <<interface>>
        +createReadOnlyUser(database: String) DbCredentials
        +createReadWriteUser(database: String) DbCredentials
        +deleteUser(username: String)
    }

    class GarageAccessTokenManager {
        -adminApiUrl: String
        -adminToken: String
    }

    class MinioAccessTokenManager {
        -endpoint: String
        -adminKey: String
    }

    class PostgresAccessTokenManager {
        -jdbcUrl: String
        -adminUser: String
        -adminPassword: String
    }

    ObjectStoreAccessTokenManager <|.. GarageAccessTokenManager : implementerar
    ObjectStoreAccessTokenManager <|.. MinioAccessTokenManager : implementerar
    DatabaseAccessTokenManager <|.. PostgresAccessTokenManager : implementerar
```

---

## Åtkomstregler

```mermaid
flowchart LR
    subgraph Roller
        A["Oprivilegierad\nanvändare"]
        B["Privilegierad\nanvändare"]
    end

    subgraph "Garage (S3)"
        RO["Read-only nyckel\n(GET på bucket)"]
        RW["Read/Write nyckel\n(GET + PUT + DELETE)"]
    end

    subgraph "PostgreSQL"
        PGRO["SELECT-rättigheter"]
        PGRW["SELECT + INSERT +\nUPDATE + DELETE"]
    end

    A -->|"kan skapa"| RO
    A -->|"kan skapa"| PGRO
    B -->|"kan skapa"| RO
    B -->|"kan skapa"| RW
    B -->|"kan skapa"| PGRO
    B -->|"kan skapa"| PGRW
```

---

## API-endpoints

```mermaid
graph LR
    subgraph "POST /api/keys/generate"
        direction TB
        IN1["Body:\nbucket, role, type"]
        OUT1["Response:\ngarage_key_id\ngarage_secret\npg_user\npg_password\nduckdb_script"]
    end

    subgraph "GET /api/keys"
        direction TB
        OUT2["Lista alla nycklar\nför inloggad användare"]
    end

    subgraph "DELETE /api/keys/:id"
        direction TB
        OUT3["Revokera nyckel\ni Garage + PostgreSQL"]
    end
```

---

## Implementationsplan

```mermaid
gantt
    title DuckLake Access Manager – Faser
    dateFormat  YYYY-MM-DD
    section Fas 1 – Grund
    Interface-design (ObjectStore + DB)       :f1a, 2025-05-01, 3d
    GarageAccessTokenManager impl             :f1b, after f1a, 4d
    PostgresAccessTokenManager impl (JDBC)    :f1c, after f1a, 4d

    section Fas 2 – API
    REST API (endpoints + autentisering)      :f2a, after f1b, 5d
    Rollhantering (privilegierad/oprivilegiad):f2b, after f2a, 3d

    section Fas 3 – UI
    Web UI – nyckelgenerering                 :f3a, after f2b, 4d
    DuckDB script-generator                   :f3b, after f3a, 2d
    Visa råa nycklar + endpoints              :f3c, after f3b, 2d

    section Fas 4 – Driftsättning
    Dockerisering + cbhcloud deployment       :f4a, after f3c, 3d
    Integration med befintliga deployments    :f4b, after f4a, 2d
    Testning + dokumentation                  :f4c, after f4b, 3d
```

---

## Output till användaren

När en nyckel genereras får användaren ett klart-att-köra DuckDB-script:

```sql
INSTALL ducklake;
INSTALL postgres;

LOAD ducklake;
LOAD postgres;

-- Genererat av DuckLake Access Manager
CREATE OR REPLACE SECRET (
    TYPE postgres,
    HOST '<postgres-host>',
    PORT 5432,
    DATABASE ducklake,
    USER '<generated-pg-user>',
    PASSWORD '<generated-pg-password>'
);

CREATE OR REPLACE SECRET garage_secret (
    TYPE s3,
    PROVIDER config,
    KEY_ID '<generated-garage-key-id>',
    SECRET '<generated-garage-secret>',
    REGION 'local',
    ENDPOINT '<garage-endpoint>',
    URL_STYLE 'path',
    USE_SSL false
);

ATTACH 'ducklake:postgres:dbname=ducklake' AS my_ducklake (
    DATA_PATH 's3://<bucket-name>/'
);

USE my_ducklake;
```

Samt råa värden för användare som vill integrera på annat sätt:

| Nyckel | Värde |
|---|---|
| Garage Endpoint | `https://...` |
| Garage Key ID | `...` |
| Garage Secret | `...` |
| PostgreSQL Host | `...` |
| PostgreSQL User | `...` |
| PostgreSQL Password | `...` |

---

## Teknisk stack (rekommendation)

| Del | Teknologi | Motivering |
|---|---|---|
| Backend/API | **Go** | Passar Garage Admin API, lätt att containerisera, används av garage-webui |
| Alternativ backend | **Java (Spring Boot)** | JDBC-stöd inbyggt, välkänt för PostgreSQL-hantering |
| Frontend | **TypeScript + React** | Samma stack som garage-webui för inspiration |
| PostgreSQL-hantering | **JDBC** (Java) eller `database/sql` (Go) | Direkt användarskapande |
| Garage-hantering | **REST API mot :3903** | Officiell admin API, OpenAPI-spec tillgänglig |

---

## Viktiga noter

- **Använd interfaces** – `ObjectStoreAccessTokenManager` och `DatabaseAccessTokenManager` – så att MinIO enkelt kan bytas mot Garage (eller vice versa) utan att ändra resten av systemet.
- **MinIO är unmaintained** – bygg primärt `GarageAccessTokenManager`, lägg till MinIO-impl endast om det behövs för lokal dev.
- **Garage Admin API port 3903** – måste vara nåbar från Access Manager-tjänsten internt på cbhcloud.
- **Olika datasets = olika buckets** – hantera behörigheter per bucket, inte globalt.
- **PostgreSQL utan SSH-tunnel** hanteras av handledaren som en systemtjänst via Helm chart – detta är inte vår uppgift.
