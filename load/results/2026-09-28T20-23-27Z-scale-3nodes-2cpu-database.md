# Base de données pendant la charge

193 relevés de `pg_stat_activity` toutes les 2 s ; 0 en échec ou hors délai.

## Disque

- journal (WAL) : 2296693 enregistrements, 376 Mo, 71250 fsync ; **fsync moyen 4.64 ms**, écriture moyenne 0.03 ms ;
- transactions validées : 185479 ; interblocages : 0 ;
- checkpointer/relation/normal : 4978 écritures (0.95 ms), 0 fsync (0.00 ms)

## Sessions non inactives, par palier

Nombre moyen (et maximal) de sessions dans chaque situation, par relevé. `age` : depuis quand la requête tourne (session active) ou la transaction est ouverte (sinon).

### Échauffement

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-queue | active | LWLock:WALWrite | 1.40 | 22 |
| praxedo-api | active | LWLock:WALWrite | 0.82 | 17 |
| praxedo-queue | active | IO:WalSync | 0.44 | 1 |
| praxedo-api | active | IO:WalSync | 0.29 | 1 |
| praxedo-queue | active | — | 0.29 | 3 |
| praxedo-api | idle in transaction | Client:ClientRead | 0.27 | 3 |
| praxedo-queue | active | Client:ClientRead | 0.09 | 2 |
| praxedo-api | active | Client:ClientRead | 0.07 | 1 |
| praxedo-api | active | — | 0.04 | 1 |
| autovacuum worker | active | IO:WalSync | 0.02 | 1 |
| praxedo-queue | active | LWLock:BufferContent | 0.02 | 1 |
| praxedo-api | idle in transaction | IO:WalSync | 0.02 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 2, praxedo-api 30, praxedo-queue 29.

### Palier 150 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-queue | active | LWLock:WALWrite | 2.77 | 24 |
| praxedo-api | active | LWLock:WALWrite | 2.37 | 30 |
| praxedo-queue | active | IO:WalSync | 0.63 | 1 |
| praxedo-queue | active | — | 0.27 | 3 |
| praxedo-queue | active | Client:ClientRead | 0.17 | 3 |
| praxedo-api | active | IO:WalSync | 0.17 | 1 |
| praxedo-api | active | — | 0.07 | 1 |
| praxedo-api | idle in transaction | Client:ClientRead | 0.07 | 1 |
| praxedo-api | idle in transaction | LWLock:WALWrite | 0.03 | 1 |
| autovacuum worker | active | IO:WalSync | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 3, praxedo-queue 6.

### Palier 200 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-api | active | LWLock:WALWrite | 8.97 | 30 |
| praxedo-queue | active | LWLock:WALWrite | 8.80 | 24 |
| praxedo-queue | active | IO:WalSync | 0.63 | 1 |
| praxedo-queue | active | — | 0.60 | 6 |
| praxedo-api | active | IO:WalSync | 0.27 | 1 |
| praxedo-api | idle in transaction | Client:ClientRead | 0.10 | 1 |
| praxedo-queue | active | Client:ClientRead | 0.07 | 1 |
| praxedo-api | active | — | 0.07 | 1 |
| praxedo-api | idle in transaction | — | 0.03 | 1 |
| praxedo-api | active | Client:ClientRead | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 3, praxedo-queue 6.

### Palier 250 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-queue | active | LWLock:WALWrite | 17.07 | 24 |
| praxedo-api | active | LWLock:WALWrite | 16.83 | 30 |
| praxedo-queue | active | IO:WalSync | 0.63 | 1 |
| praxedo-api | active | IO:WalSync | 0.27 | 1 |
| praxedo-queue | active | — | 0.27 | 4 |
| praxedo-queue | active | LWLock:WALInsert | 0.27 | 8 |
| praxedo-api | active | LWLock:WALInsert | 0.13 | 4 |
| praxedo-api | active | LWLock:BufferContent | 0.10 | 3 |
| praxedo-queue | active | IO:WalInitSync | 0.10 | 1 |
| praxedo-queue | active | Lock:transactionid | 0.03 | 1 |
| praxedo-queue | active | LWLock:BufferContent | 0.03 | 1 |
| autovacuum worker | active | LWLock:WALWrite | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 3, praxedo-api 10, praxedo-queue 6.

### Palier 300 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-api | active | LWLock:WALWrite | 18.71 | 30 |
| praxedo-queue | active | LWLock:WALWrite | 18.03 | 24 |
| praxedo-queue | active | IO:WalSync | 0.61 | 1 |
| praxedo-api | active | IO:WalSync | 0.29 | 1 |
| praxedo-queue | active | Client:ClientRead | 0.13 | 4 |
| autovacuum worker | active | Timeout:VacuumDelay | 0.03 | 1 |
| autovacuum worker | active | LWLock:BufferContent | 0.03 | 1 |
| praxedo-queue | active | — | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 3, praxedo-queue 6.

## Blocages

Relevés où au moins 5 sessions praxedo sont engagées depuis plus de 2 s.

| Heure | Sessions | Attentes | Bloquées par | Exemple de requête |
|---|---:|---|---|---|
| 20:28:45 | 21 | 20 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `WITH candidate AS ( SELECT id FROM stored_file WHERE status IN ('AWAITING_SCAN', 'RETRY_WAIT') AND next_attemp` |
| 20:28:55 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalInitSync | — | `COMMIT` |
| 20:28:57 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalInitSync | — | `COMMIT` |
| 20:29:05 | 14 | 13 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:29:07 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:29:17 | 12 | 12 active/LWLock:WALWrite | — | `COMMIT` |
| 20:29:19 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:29:23 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:29:33 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:29:41 | 38 | 37 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:29:45 | 32 | 31 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:29:51 | 8 | 7 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:29:53 | 52 | 51 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:29:59 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:30:01 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:30:07 | 24 | 23 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:30:21 | 50 | 49 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |

## Journal de PostgreSQL

- attentes de verrou : 0
- instructions > 1 s : 843
  - `2026-09-28 20:26:45.150 UTC [125] LOG:  duration: 1832.500 ms  execute S_1: COMMIT`
  - `2026-09-28 20:26:45.150 UTC [137] LOG:  duration: 1833.173 ms  execute S_1: COMMIT`
  - `2026-09-28 20:26:45.150 UTC [126] LOG:  duration: 1804.794 ms  execute S_1: COMMIT`
  - `2026-09-28 20:26:45.150 UTC [138] LOG:  duration: 1803.159 ms  execute S_1: COMMIT`
  - `2026-09-28 20:26:45.150 UTC [139] LOG:  duration: 1802.865 ms  execute S_1: COMMIT`
- autovacuum : 61
  - `2026-09-28 20:24:13.494 UTC [195] LOG:  automatic analyze of table "keycloak.public.databasechangelog"`
  - `2026-09-28 20:24:13.513 UTC [195] LOG:  automatic analyze of table "keycloak.pg_catalog.pg_type"`
  - `2026-09-28 20:24:13.519 UTC [195] LOG:  automatic vacuum of table "keycloak.pg_catalog.pg_attribute": index scans: 1`
  - `2026-09-28 20:24:13.537 UTC [195] LOG:  automatic analyze of table "keycloak.pg_catalog.pg_attribute"`
  - `2026-09-28 20:24:13.540 UTC [195] LOG:  automatic vacuum of table "keycloak.pg_catalog.pg_class": index scans: 1`
- checkpoints : 1
  - `2026-09-28 20:28:38.929 UTC [64] LOG:  checkpoint starting: time`
- erreurs : 0

## Connexions gardées plus de 2 s (détection de fuite Hikari)

759 alertes. Par fil d'exécution et lieu de prise :

- 384 × tomcat-handler-N ← com.praxedo.securefiles.infrastructure.persistence.common.adapter.SpringTransactionRunner.inTransaction (SpringTransactionRunner.java:32)
- 159 × scan-worker-N ← com.praxedo.securefiles.infrastructure.persistence.file.adapter.JdbcFileWorkQueue.lambda$markAvailable$3 (JdbcFileWorkQueue.java:234)
- 120 × scan-worker-N ← com.praxedo.securefiles.infrastructure.persistence.file.adapter.JdbcFileWorkQueue.lambda$writeVerdict$2 (JdbcFileWorkQueue.java:199)
- 96 × scan-worker-N ← com.praxedo.securefiles.infrastructure.persistence.file.adapter.JdbcFileWorkQueue.lambda$claimNextDue$0 (JdbcFileWorkQueue.java:141)

