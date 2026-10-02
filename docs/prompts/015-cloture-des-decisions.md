# 015 — Clôture : décisions ouvertes et énoncé

- **Date** : 2026-10-01
- **Outil** : Claude Opus 5.5 (Claude Code)
- **Objectif** : faire l'inventaire de ce qui reste ouvert avant de rendre le
  projet, puis clore ce qui peut l'être
- **Phase du projet** : livraison — session d'architecture (`docs/`, `AGENTS.md`)

## Prompt

> Le projet approche de sa clôture. Fais l'inventaire de ce qui reste
> ouvert — décisions du registre, problématiques, constats d'audit — en
> vérifiant chaque point dans le code, le contrat et les ADR plutôt que dans
> les documents de cadrage. Clôture ce qui est déjà tranché, et liste ce qui
> reste à faire.

## Le raisonnement

| Étape | Position | Ce qui l'a fait évoluer |
|---|---|---|
| 1. L'inventaire | Le registre `D-01…D-17` était entièrement marqué `OUVERT`, alors que le code, le contrat et les ADR en avaient tranché presque tout | Chaque décision comparée au code, au contrat et aux ADR, pas aux documents de cadrage |
| 2. Le vrai reste | Peu de décisions, mais des tâches : les lots 1 et 2 de l'audit (`docs/32`), recommandés avant livraison et **non faits** (secrets par défaut, rôle PostgreSQL superutilisateur, ports publiés, actuator public) ; Playwright | Vérifié dans `application.yml`, `docker-compose.yml` et la CI, pas sur la foi de l'audit |
| 3. L'énoncé (`D-17`) | Dépôt public et énoncé reproduit mot pour mot : je l'ai signalé comme urgent | Le porteur a choisi le résumé + la table `EX-xx` |
| 4. La clôture | Registre clos, `D-18` et `D-19` (issues de la contre-analyse, absentes du registre) ajoutées ; `AGENTS.md` remis à l'état réel ; bandeaux sur les documents dépassés | Dérives déjà relevées par `backend/ARCHITECTURE.md` §17, désormais corrigées |

## Ce que j'ai retenu

| Décision | Raison |
|---|---|
| Registre `D-01…D-19` clos : 18 actées, `D-08` reportée | Chaque ligne renvoie à l'ADR ou au document qui porte la décision ; les recommandations d'origine restent, pour montrer l'écart entre l'analyse et le choix final (`D-15`, `D-05`, `D-03`) |
| `docs/00-enonce.md` réduit à un résumé + la table `EX-01…EX-15` | Décision `D-17` ; la traçabilité des exigences est intacte |
| `AGENTS.md` §4 : état réel, et une prochaine action qui n'est plus « initialiser Git » mais les lots 1 et 2 de l'audit | Le fichier se lit en premier : il ne doit pas annoncer un back-end « non démarré » |
| Bandeaux sur `docs/23` et `docs/31` ; index `docs/README.md` à jour | Documents gardés pour l'historique du raisonnement, mais signalés comme dépassés |

## Ce que j'ai rejeté ou laissé, et pourquoi

| Point | Pourquoi |
|---|---|
| Proposer de passer le dépôt en privé | Écarté par le porteur : le dépôt doit rester public pour être partagé |
| Réécrire l'historique Git dès maintenant | Prévu en fin de projet ; un push forcé sur `main` n'était pas demandé |
| Implémenter les lots de l'audit dans cette passe | Hors du périmètre demandé (clôture documentaire) ; listés en prochaine action |

## Vérifications effectuées

- Chaque décision du registre vérifiée à sa source : ADR, `contracts/README.md`
  (§2.7, §2.9, §2.10), `docker-compose.yml`, `QuarantineSweeper` (fichiers
  infectés épargnés), `backend/ARCHITECTURE.md` (§4, §6).
