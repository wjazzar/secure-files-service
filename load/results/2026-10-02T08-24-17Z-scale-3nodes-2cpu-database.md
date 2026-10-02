# Base de données pendant la charge

192 relevés de `pg_stat_activity` toutes les 2 s ; 0 en échec ou hors délai.

## Disque

- journal (WAL) : 2035766 enregistrements, 330 Mo, 66432 fsync ; **fsync moyen 4.96 ms**, écriture moyenne 0.03 ms ;
- transactions validées : 164561 ; interblocages : 0 ;
- checkpointer/relation/normal : 4297 écritures (0.90 ms), 0 fsync (0.00 ms)

## Sessions non inactives, par palier

Nombre moyen (et maximal) de sessions dans chaque situation, par relevé. `age` : depuis quand la requête tourne (session active) ou la transaction est ouverte (sinon).

### Échauffement

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-api | idle in transaction | Client:ClientRead | 0.84 | 9 |
| praxedo-queue | active | LWLock:WALWrite | 0.80 | 5 |
| praxedo-queue | active | IO:WalSync | 0.40 | 1 |
| praxedo-queue | active | — | 0.29 | 3 |
| praxedo-api | active | IO:WalSync | 0.27 | 1 |
| praxedo-api | active | LWLock:WALWrite | 0.11 | 1 |
| praxedo-api | active | — | 0.11 | 1 |
| praxedo-queue | active | Client:ClientRead | 0.04 | 1 |
| praxedo-api | active | Client:ClientRead | 0.04 | 1 |
| praxedo-queue | active | IO:WalWrite | 0.02 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 5, praxedo-api 29, praxedo-queue 28.

### Palier 150 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-queue | active | LWLock:WALWrite | 2.33 | 23 |
| praxedo-api | active | LWLock:WALWrite | 1.90 | 30 |
| praxedo-queue | active | IO:WalSync | 0.63 | 1 |
| praxedo-queue | active | — | 0.47 | 3 |
| praxedo-api | active | IO:WalSync | 0.23 | 1 |
| praxedo-api | idle in transaction | Client:ClientRead | 0.07 | 1 |
| praxedo-api | active | Client:ClientRead | 0.07 | 1 |
| praxedo-queue | active | LWLock:BufferContent | 0.03 | 1 |
| praxedo-queue | active | Client:ClientRead | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 9, praxedo-api 30, praxedo-queue 27.

### Palier 200 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-api | active | LWLock:WALWrite | 17.73 | 30 |
| praxedo-queue | active | LWLock:WALWrite | 14.93 | 24 |
| praxedo-queue | active | IO:WalSync | 0.67 | 1 |
| praxedo-api | active | IO:WalSync | 0.30 | 1 |
| praxedo-queue | active | — | 0.13 | 2 |
| praxedo-api | idle in transaction | Client:ClientRead | 0.10 | 2 |
| praxedo-api | active | — | 0.10 | 1 |
| autovacuum worker | active | Timeout:VacuumDelay | 0.03 | 1 |
| autovacuum worker | active | LWLock:WALWrite | 0.03 | 1 |
| praxedo-api | idle in transaction | — | 0.03 | 1 |
| praxedo-queue | active | Client:ClientRead | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 9, praxedo-queue 6.

### Palier 250 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-api | active | LWLock:WALWrite | 19.80 | 30 |
| praxedo-queue | active | LWLock:WALWrite | 16.97 | 24 |
| praxedo-queue | active | IO:WalSync | 0.57 | 1 |
| praxedo-api | active | IO:WalSync | 0.40 | 1 |
| praxedo-queue | active | — | 0.30 | 3 |
| autovacuum worker | active | LWLock:WALWrite | 0.10 | 1 |
| autovacuum worker | active | — | 0.07 | 1 |
| praxedo-api | active | Client:ClientRead | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 9, praxedo-queue 6.

### Palier 300 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-api | active | LWLock:WALWrite | 18.38 | 30 |
| praxedo-queue | active | LWLock:WALWrite | 17.90 | 24 |
| praxedo-queue | active | IO:WalSync | 0.59 | 1 |
| praxedo-queue | active | — | 0.52 | 6 |
| praxedo-api | idle in transaction | Client:ClientRead | 0.38 | 7 |
| praxedo-api | active | IO:WalSync | 0.24 | 1 |
| praxedo-queue | active | IO:WalInitSync | 0.07 | 1 |
| autovacuum worker | active | — | 0.07 | 1 |
| praxedo-api | active | — | 0.03 | 1 |
| praxedo-api | idle in transaction | — | 0.03 | 1 |
| praxedo-api | idle in transaction | LWLock:WALWrite | 0.03 | 1 |
| autovacuum worker | active | LWLock:WALWrite | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 9, praxedo-api 30, praxedo-queue 7.

## Blocages

Relevés où au moins 5 sessions praxedo sont engagées depuis plus de 2 s.

| Heure | Sessions | Attentes | Bloquées par | Exemple de requête |
|---|---:|---|---|---|
| 08:28:19 | 49 | 48 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:28:21 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:28:23 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:28:45 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:29:03 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:29:05 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:29:07 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:29:35 | 41 | 40 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:29:45 | 31 | 30 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:29:53 | 53 | 52 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:29:55 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:29:57 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:29:59 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:30:01 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:30:24 | 42 | 41 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:30:32 | 53 | 52 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:30:45 | 8 | 7 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:30:46 | 8 | 7 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:30:48 | 46 | 45 active/LWLock:WALWrite, 1 active/IO:WalInitSync | — | `COMMIT` |
| 08:30:50 | 54 | 53 active/LWLock:WALWrite, 1 active/IO:WalInitSync | — | `COMMIT` |
| 08:30:58 | 39 | 38 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:31:04 | 38 | 37 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:31:06 | 50 | 50 active/LWLock:WALWrite | — | `COMMIT` |

Relevés lents (> 1 s, la base répondait mal) : 08:30:45 1.1 s.

## Journal de PostgreSQL

- attentes de verrou : 0
- instructions > 1 s : 1084
  - `2026-10-02 08:27:37.261 UTC [140] LOG:  duration: 1839.768 ms  execute S_1: COMMIT`
  - `2026-10-02 08:27:37.748 UTC [137] LOG:  duration: 2308.630 ms  execute S_1: COMMIT`
  - `2026-10-02 08:27:37.748 UTC [128] LOG:  duration: 2314.930 ms  execute S_1: COMMIT`
  - `2026-10-02 08:27:37.748 UTC [87] LOG:  duration: 2293.043 ms  execute S_1: COMMIT`
  - `2026-10-02 08:27:37.748 UTC [139] LOG:  duration: 2288.436 ms  execute S_1: COMMIT`
- autovacuum : 61
  - `2026-10-02 08:25:06.424 UTC [214] LOG:  automatic analyze of table "template1.pg_catalog.pg_type"`
  - `2026-10-02 08:25:06.449 UTC [214] LOG:  automatic analyze of table "template1.pg_catalog.pg_attribute"`
  - `2026-10-02 08:25:06.466 UTC [214] LOG:  automatic analyze of table "template1.pg_catalog.pg_class"`
  - `2026-10-02 08:25:06.478 UTC [214] LOG:  automatic analyze of table "template1.pg_catalog.pg_constraint"`
  - `2026-10-02 08:25:06.482 UTC [214] LOG:  automatic analyze of table "template1.pg_catalog.pg_rewrite"`
- checkpoints : 1
  - `2026-10-02 08:29:26.900 UTC [58] LOG:  checkpoint starting: time`
- erreurs : 0

## Connexions gardées plus de 2 s (détection de fuite Hikari)

938 alertes. Par fil d'exécution et lieu de prise :

- 508 × tomcat-handler-N ← com.praxedo.securefiles.infrastructure.persistence.common.adapter.SpringTransactionRunner.inTransaction (SpringTransactionRunner.java:32)
- 182 × scan-worker-N ← com.praxedo.securefiles.infrastructure.persistence.file.adapter.JdbcFileWorkQueue.lambda$writeVerdict$2 (JdbcFileWorkQueue.java:200)
- 125 × scan-worker-N ← com.praxedo.securefiles.infrastructure.persistence.file.adapter.JdbcFileWorkQueue.lambda$claimNextDue$0 (JdbcFileWorkQueue.java:142)
- 123 × scan-worker-N ← com.praxedo.securefiles.infrastructure.persistence.file.adapter.JdbcFileWorkQueue.lambda$markAvailable$3 (JdbcFileWorkQueue.java:235)

