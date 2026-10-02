# Base de données pendant la charge

178 relevés de `pg_stat_activity` toutes les 2 s ; 0 en échec ou hors délai.

## Disque

- journal (WAL) : 1919137 enregistrements, 309 Mo, 86386 fsync ; **fsync moyen 3.06 ms**, écriture moyenne 0.01 ms ;
- transactions validées : 150356 ; interblocages : 0 ;
- checkpointer/relation/normal : 3129 écritures (1.11 ms), 0 fsync (0.00 ms)

## Sessions non inactives, par palier

Nombre moyen (et maximal) de sessions dans chaque situation, par relevé. `age` : depuis quand la requête tourne (session active) ou la transaction est ouverte (sinon).

### Échauffement

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-queue | active | LWLock:WALWrite | 1.07 | 7 |
| praxedo-queue | active | IO:WalSync | 0.60 | 1 |
| praxedo-api | active | LWLock:WALWrite | 0.57 | 10 |
| praxedo-api | idle in transaction | Client:ClientRead | 0.13 | 4 |
| praxedo-queue | active | — | 0.13 | 1 |
| praxedo-api | active | IO:WalSync | 0.07 | 1 |
| praxedo-api | active | Client:ClientRead | 0.03 | 1 |
| praxedo-api | active | — | 0.03 | 1 |
| praxedo-queue | active | IO:WalInitSync | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 2, praxedo-api 10, praxedo-queue 10.

### Palier 100 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-queue | active | IO:WalSync | 0.47 | 1 |
| praxedo-queue | active | LWLock:WALWrite | 0.43 | 5 |
| praxedo-api | active | LWLock:WALWrite | 0.33 | 7 |
| praxedo-api | active | IO:WalSync | 0.23 | 1 |
| praxedo-queue | active | — | 0.23 | 2 |
| praxedo-queue | active | Client:ClientRead | 0.07 | 1 |
| praxedo-api | active | — | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 2, praxedo-api 9, praxedo-queue 8.

### Palier 130 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-queue | active | LWLock:WALWrite | 1.33 | 8 |
| praxedo-api | active | LWLock:WALWrite | 0.70 | 9 |
| praxedo-queue | active | IO:WalSync | 0.53 | 1 |
| praxedo-queue | active | — | 0.33 | 3 |
| praxedo-api | active | IO:WalSync | 0.27 | 1 |
| praxedo-queue | active | Client:ClientRead | 0.03 | 1 |
| praxedo-queue | active | IO:WalInitSync | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 2, praxedo-api 10, praxedo-queue 9.

### Palier 160 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-queue | active | LWLock:WALWrite | 1.43 | 4 |
| praxedo-queue | active | IO:WalSync | 0.50 | 1 |
| praxedo-queue | active | — | 0.30 | 2 |
| praxedo-api | active | LWLock:WALWrite | 0.27 | 2 |
| praxedo-api | active | IO:WalSync | 0.27 | 1 |
| praxedo-api | active | — | 0.07 | 1 |
| praxedo-api | idle in transaction | Client:ClientRead | 0.07 | 1 |
| praxedo-queue | active | Client:ClientRead | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 2, praxedo-api 9, praxedo-queue 7.

### Palier 200 fichiers/s

| Pool | État | Attente | Moyenne | Max |
|---|---|---|---:|---:|
| praxedo-api | active | LWLock:WALWrite | 6.33 | 10 |
| praxedo-queue | active | LWLock:WALWrite | 5.20 | 8 |
| praxedo-queue | active | IO:WalSync | 0.53 | 1 |
| praxedo-api | active | IO:WalSync | 0.37 | 1 |
| praxedo-queue | active | — | 0.07 | 1 |
| praxedo-queue | active | Client:ClientRead | 0.07 | 2 |
| autovacuum worker | active | LWLock:WALWrite | 0.07 | 1 |
| praxedo-queue | active | Lock:transactionid | 0.03 | 1 |
| praxedo-api | active | — | 0.03 | 1 |

Inactives au dernier relevé : PostgreSQL JDBC Driver 30, praxedo-api 7, praxedo-queue 8.

## Blocages

Relevés où au moins 5 sessions praxedo sont engagées depuis plus de 2 s.

| Heure | Sessions | Attentes | Bloquées par | Exemple de requête |
|---|---:|---|---|---|
| 20:22:09 | 17 | 16 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:22:11 | 18 | 17 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:22:21 | 17 | 16 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:22:37 | 18 | 17 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:22:39 | 18 | 17 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:22:41 | 18 | 17 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:22:43 | 18 | 17 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:22:53 | 14 | 13 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |
| 20:23:05 | 17 | 16 active/LWLock:WALWrite, 1 active/IO:WalSync | — | `COMMIT` |

## Journal de PostgreSQL

- attentes de verrou : 0
- instructions > 1 s : 131
  - `2026-09-28 20:21:57.159 UTC [89] LOG:  duration: 1449.410 ms  execute S_1: COMMIT`
  - `2026-09-28 20:21:57.159 UTC [87] LOG:  duration: 1450.062 ms  execute S_1: COMMIT`
  - `2026-09-28 20:21:57.159 UTC [84] LOG:  duration: 1449.269 ms  execute S_2: COMMIT`
  - `2026-09-28 20:21:57.159 UTC [88] LOG:  duration: 1449.927 ms  execute S_1: COMMIT`
  - `2026-09-28 20:21:57.159 UTC [86] LOG:  duration: 1440.702 ms  execute S_1: COMMIT`
- autovacuum : 51
  - `2026-09-28 20:17:29.569 UTC [184] LOG:  automatic vacuum of table "praxedo.public.stored_file": index scans: 1`
  - `2026-09-28 20:17:29.591 UTC [184] LOG:  automatic analyze of table "praxedo.public.stored_file"`
  - `2026-09-28 20:17:29.595 UTC [184] LOG:  automatic vacuum of table "praxedo.public.file_audit_event": index scans: 0`
  - `2026-09-28 20:17:29.603 UTC [184] LOG:  automatic analyze of table "praxedo.public.file_audit_event"`
  - `2026-09-28 20:17:29.609 UTC [184] LOG:  automatic analyze of table "praxedo.pg_catalog.pg_depend"`
- checkpoints : 1
  - `2026-09-28 20:21:49.717 UTC [64] LOG:  checkpoint starting: time`
- erreurs : 0

## Connexions gardées plus de 2 s (détection de fuite Hikari)

124 alertes. Par fil d'exécution et lieu de prise :

- 72 × tomcat-handler-N ← com.praxedo.securefiles.infrastructure.persistence.common.adapter.SpringTransactionRunner.inTransaction (SpringTransactionRunner.java:32)
- 24 × scan-worker-N ← com.praxedo.securefiles.infrastructure.persistence.file.adapter.JdbcFileWorkQueue.lambda$writeVerdict$2 (JdbcFileWorkQueue.java:199)
- 18 × scan-worker-N ← com.praxedo.securefiles.infrastructure.persistence.file.adapter.JdbcFileWorkQueue.lambda$markAvailable$3 (JdbcFileWorkQueue.java:234)
- 10 × scan-worker-N ← com.praxedo.securefiles.infrastructure.persistence.file.adapter.JdbcFileWorkQueue.lambda$claimNextDue$0 (JdbcFileWorkQueue.java:141)

