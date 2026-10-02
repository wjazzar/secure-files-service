# ADR-0011 — Architecture hexagonale vérifiée : ports d'entrée, ports de sortie

- **Statut** : Accepté — signé par le porteur du projet le 2026-10-01 ; rédigé par Claude (Opus 5.5), session back-end, à la demande du porteur du projet
- **Date** : 2026-09-27
- **Décision du registre** : D-16
- **Exigences concernées** : EX-11, EX-15

## Contexte

Le code avait des couches dirigées vers l'intérieur et des **ports de sortie**,
mais **aucun port d'entrée** : les contrôleurs appelaient des services concrets.
En relisant, le porteur du projet l'a relevé : sans port d'entrée, rien ne dit
ce que le cœur *offre*, ni par où on y entre. L'audit des dépendances a montré
pire : le planificateur appelait directement la file de travail et le magasin
d'idempotence (des ports de sortie), les métriques lisaient l'adaptateur de
persistance, et le web et la sécurité dépendaient l'un de l'autre.

## Décision

- **Six ports d'entrée** (`application/file/port/in`), un par intention d'un
  acteur extérieur : déposer, consulter, télécharger, analyser, entretenir,
  surveiller. Chacun est implémenté par un service (`application/file/service`).
- Les **adaptateurs pilotants** (web, planification, métriques) n'appellent que
  ces ports. Les **adaptateurs pilotés** (persistance, stockage, antivirus,
  signature) implémentent les ports de sortie et n'appellent jamais le cœur.
- Les adaptateurs **ne dépendent jamais les uns des autres** : la sécurité HTTP
  (authentification, propriétaire courant) passe dans l'adaptateur web, dont
  elle fait partie ; les métriques passent par un port d'entrée et un nouveau
  port de sortie (`OperationalReadings`).
- Seule la **racine de composition** connaît les services, et publie chacun
  sous son port d'entrée.
- Le code est rangé **par concept, puis par nature**, dans toutes les couches.
- `HexagonalArchitectureTest` et `CodeLayoutRulesTest` (ArchUnit) le vérifient
  à chaque build ; une violation volontaire a été attrapée avant d'être retirée.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| Garder les services concrets appelés par les contrôleurs | Le cœur n'expose aucune intention ; un adaptateur peut atteindre n'importe quoi |
| Un port d'entrée par méthode | Six interfaces suffisent à dire ce que le cœur offre ; une par méthode multiplierait les fichiers sans rien clarifier |
| `Architectures.onionArchitecture()` d'ArchUnit | Règles explicites préférées : chacune dit ce qu'elle protège, et le message d'échec le répète |

## Conséquences

**Positives** — chaque adaptateur se remplace seul ; ce que le cœur offre se lit
dans un dossier ; les tests pilotent le cœur par les mêmes ports que la
production ; la règle ne dépend pas de la vigilance d'une revue.

**Négatives** — une interface par cas d'usage et une racine de composition à
tenir à jour ; des chemins de paquets plus longs.

**Ce qui la remettrait en cause** — rien de prévisible : c'est la structure qui
rend les autres décisions remplaçables. Un second concept (des utilisateurs, des
espaces partagés) s'y ajoute en dossier frère de `file`.
