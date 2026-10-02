# Base de données pendant la charge

179 relevés de `pg_stat_activity` toutes les 2 s ; 0 en échec ou hors délai.

## Disque

- journal (WAL) : 1580045 enregistrements, 256 Mo, 70506 fsync ; **fsync moyen 3.74 ms**, écriture moyenne 0.03 ms ;
- transactions validées : 123944 ; interblocages : 0 ;
- checkpointer/relation/normal : 2834 écritures (0.30 ms), 0 fsync (0.00 ms)

## Sessions non inactives, par palier

Nombre moyen (et maximal) de sessions dans chaque situation, par relevé. `age` : depuis quand la requête tourne (session active) ou la transaction est ouverte (sinon).

### Échauffement

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-queue | active | LWLock:WALWrite | 0.70 | 4 |
| praxedo-queue | active | IO:WalSync | 0.37 | 1 |
| praxedo-queue | active | — | 0.37 | 3 |
| praxedo-api | active | LWLock:WALWrite | 0.23 | 3 |
| praxedo-api | active | IO:WalSync | 0.20 | 1 |
| praxedo-api | idle in transaction | Client:ClientRead | 0.17 | 1 |
| praxedo-api | active | — | 0.03 | 1 |
| praxedo-api | idle in transaction | — | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 2, praxedo-api 9, praxedo-queue 10.

### Palier 100 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-queue | active | LWLock:WALWrite | 0.57 | 4 |
| praxedo-queue | active | IO:WalSync | 0.47 | 1 |
| praxedo-api | active | IO:WalSync | 0.30 | 1 |
| praxedo-api | active | LWLock:WALWrite | 0.17 | 1 |
| praxedo-api | active | — | 0.07 | 1 |
| praxedo-queue | active | — | 0.03 | 1 |
| praxedo-api | idle in transaction | Client:ClientRead | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 2, praxedo-api 10, praxedo-queue 9.

### Palier 130 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-queue | active | LWLock:WALWrite | 1.00 | 5 |
| praxedo-queue | active | IO:WalSync | 0.67 | 1 |
| praxedo-api | active | LWLock:WALWrite | 0.37 | 4 |
| praxedo-queue | active | — | 0.37 | 2 |
| praxedo-api | active | IO:WalSync | 0.13 | 1 |
| praxedo-api | active | — | 0.07 | 1 |
| praxedo-api | idle in transaction | LWLock:WALWrite | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 2, praxedo-api 10, praxedo-queue 9.

### Palier 160 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-queue | active | LWLock:WALWrite | 4.57 | 8 |
| praxedo-api | active | LWLock:WALWrite | 2.60 | 10 |
| praxedo-queue | active | IO:WalSync | 0.70 | 1 |
| praxedo-api | active | IO:WalSync | 0.17 | 1 |
| praxedo-queue | active | — | 0.07 | 1 |
| praxedo-api | active | — | 0.03 | 1 |
| praxedo-api | idle in transaction | Client:ClientRead | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 2, praxedo-api 9, praxedo-queue 2.

### Palier 200 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-queue | active | LWLock:WALWrite | 3.62 | 8 |
| praxedo-api | active | LWLock:WALWrite | 1.45 | 10 |
| praxedo-api | active | LWLock:WALInsert | 1.03 | 10 |
| praxedo-queue | active | IO:WalSync | 0.72 | 1 |
| praxedo-queue | active | LWLock:BufferContent | 0.52 | 5 |
| praxedo-queue | active | LWLock:WALInsert | 0.21 | 2 |
| praxedo-queue | active | — | 0.14 | 2 |
| praxedo-api | active | IO:WalSync | 0.10 | 1 |
| autovacuum worker | active | LWLock:WALWrite | 0.10 | 1 |
| autovacuum worker | active | — | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 2, praxedo-api 9, praxedo-queue 10.

## Blocages

Relevés où au moins 5 sessions praxedo sont engagées depuis plus de 2 s.

| Heure | Sessions | Attentes | Bloquées par | Exemple de requête |
|---|---:|---|---|---|
| 08:21:57 | 18 | 17 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:22:03 | 7 | 6 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:23:12 | 18 | 12 active/LWLock:WALInsert, 5 active/LWLock:BufferContent, 1 active/LWLock:WALWrite | — | `COMMIT` |
| 08:23:14 | 18 | 12 active/LWLock:WALInsert, 5 active/LWLock:BufferContent, 1 active/LWLock:WALWrite | — | `COMMIT` |
| 08:23:24 | 7 | 7 active/LWLock:WALWrite | — | `UPDATE stored_file SET status = 'AVAILABLE', storage_area = 'SERVABLE', status_reason = NULL, lease_token = NU` |
| 08:23:28 | 7 | 6 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `WITH candidate AS ( SELECT id FROM stored_file WHERE status IN ('AWAITING_SCAN', 'RETRY_WAIT') AND next_attemp` |
| 08:23:39 | 7 | 6 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `UPDATE stored_file SET status = CAST($1 AS file_status), status_reason = $2, scan_result = CAST($3 AS scan_res` |
| 08:23:42 | 8 | 7 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `UPDATE stored_file SET status = 'AVAILABLE', storage_area = 'SERVABLE', status_reason = NULL, lease_token = NU` |
| 08:23:54 | 18 | 17 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 08:23:57 | 8 | 7 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |

Relevés lents (> 1 s, la base répondait mal) : 08:23:54 2.5 s.

## Journal de PostgreSQL

- attentes de verrou : 0
- instructions > 1 s : 123
  - `2026-10-02 08:21:53.615 UTC [92] LOG:  duration: 1013.224 ms  execute S_1: COMMIT`
  - `2026-10-02 08:21:53.615 UTC [91] LOG:  duration: 1022.387 ms  execute S_1: COMMIT`
  - `2026-10-02 08:21:53.615 UTC [89] LOG:  duration: 1034.027 ms  execute S_1: COMMIT`
  - `2026-10-02 08:21:53.615 UTC [88] LOG:  duration: 1041.011 ms  execute S_1: COMMIT`
  - `2026-10-02 08:21:53.615 UTC [90] LOG:  duration: 1028.727 ms  execute S_1: COMMIT`
- autovacuum : 60
  - `2026-10-02 08:18:13.265 UTC [180] LOG:  automatic analyze of table "template1.pg_catalog.pg_type"`
  - `2026-10-02 08:18:13.282 UTC [180] LOG:  automatic analyze of table "template1.pg_catalog.pg_attribute"`
  - `2026-10-02 08:18:13.293 UTC [180] LOG:  automatic analyze of table "template1.pg_catalog.pg_class"`
  - `2026-10-02 08:18:13.304 UTC [180] LOG:  automatic analyze of table "template1.pg_catalog.pg_constraint"`
  - `2026-10-02 08:18:13.311 UTC [180] LOG:  automatic analyze of table "template1.pg_catalog.pg_rewrite"`
- checkpoints : 1
  - `2026-10-02 08:22:33.061 UTC [58] LOG:  checkpoint starting: time`
- erreurs : 0

## Connexions gardées plus de 2 s (détection de fuite Hikari)

166 alertes. Par fil d'exécution et lieu de prise :

- 56 × tomcat-handler-N ← com.praxedo.securefiles.infrastructure.persistence.common.adapter.SpringTransactionRunner.inTransaction (SpringTransactionRunner.java:32)
- 40 × scan-worker-N ← com.praxedo.securefiles.infrastructure.persistence.file.adapter.JdbcFileWorkQueue.lambda$writeVerdict$2 (JdbcFileWorkQueue.java:200)
- 36 × scan-worker-N ← com.praxedo.securefiles.infrastructure.persistence.file.adapter.JdbcFileWorkQueue.lambda$claimNextDue$0 (JdbcFileWorkQueue.java:142)
- 34 × scan-worker-N ← com.praxedo.securefiles.infrastructure.persistence.file.adapter.JdbcFileWorkQueue.lambda$markAvailable$3 (JdbcFileWorkQueue.java:235)

