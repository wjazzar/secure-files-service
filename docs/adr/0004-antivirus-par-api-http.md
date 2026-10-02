# ADR-0004 — L'antivirus est consommé par une API HTTP

- **Statut** : Accepté — signé par le porteur du projet le 2026-10-01 ; rédigé par Claude (Opus 5.5), session back-end
- **Date** : 2026-09-27
- **Décision du registre** : D-01
- **Exigences concernées** : EX-03, EX-08

## Contexte

L'énoncé impose de « déléguer l'analyse à un antivirus disponible via une
API », en laissant le moteur libre. Le porteur du projet a précisé (26/09) que
la performance du moteur importait peu, mais qu'il fallait une API, si possible
en conteneur.

## Décision

**ClamAV derrière `ajilaag/clamav-rest`** (`POST /scanHandlerBody`), dans un
conteneur distinct, image épinglée par digest et **dérivée** pour forcer
`AlertExceedsMax`. L'adaptateur est un client HTTP du JDK, forcé en HTTP/1.1,
qui envoie le corps **en flux** avec sa longueur. Traduction : `200` → sain ;
`406` → infecté, **sauf** `Heuristics.Limits.Exceeded…` → non analysable ; `412`
→ non analysable ; `413`, délai dépassé, coupure, réponse illisible → **panne**,
jamais un verdict. Un portillon de santé précède chaque prise de travail : une
panne de l'antivirus ne consomme aucune tentative.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| Protocole `clamd` en TCP (`INSTREAM`), client écrit à la main | Le plus direct, mais ce n'est pas « une API » au sens de l'énoncé. Reste un repli possible derrière le même port |
| Service d'analyse en ligne | Le contenu quitterait l'infrastructure ; dépendance réseau externe |

## Conséquences

**Positives** — l'énoncé est pris au mot ; le moteur est remplaçable derrière le
port ; le double de test (WireMock) parle le même protocole.

**Négatives** — une dépendance à un enrobage tiers sur le chemin de sécurité,
compensée par l'épinglage, la lecture de son code et des tests contre le vrai
conteneur ; les corps de réponse ne sont pas du JSON malgré leur en-tête.

**Ce qui la remettrait en cause** — un enrobage abandonné ou modifié en amont, ou
une exigence de plusieurs moteurs.
