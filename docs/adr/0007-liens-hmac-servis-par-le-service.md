# ADR-0007 — Liens de téléchargement HMAC, contenu servi par le service

- **Statut** : **Remplacé par [ADR-0013](0013-telechargement-par-l-identite-de-l-appelant.md)** (29/09) — depuis la session par cookie (ADR-0012), ce lien doublait l'authentification
- **Date** : 2026-09-27
- **Décision du registre** : D-05
- **Exigences concernées** : EX-03, EX-04

## Contexte

Un navigateur ne peut pas porter d'en-tête `Authorization` sur un lien natif, et
lire 500 Mo par `fetch` les chargerait en mémoire. Le contrat prévoit donc un
lien signé de courte durée, et exige que l'état soit **revérifié au moment de
servir**.

## Décision

Un jeton `base64url(fichier:échéance:propriétaire)` signé HMAC-SHA256, comparé
en temps constant, valable 60 s. Le **service** sert le contenu : il relit la
ligne, vérifie qu'elle est servable, ouvre l'objet avec l'identité de livraison
et écrit la réponse à la main (`attachment`, `application/octet-stream`,
`nosniff`, une plage d'octets au plus). Chaque téléchargement est inscrit au
journal d'audit avant le premier octet.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| URL présignée du stockage | Le stockage ignore les états : un lien émis avant un changement d'état resterait valable. Elle publierait aussi l'adresse du stockage |

## Conséquences

**Positives** — la revérification exigée est tenue ; un lien ne vaut que pour un
fichier, un propriétaire et une minute ; l'audit est centralisé.

**Négatives** — la bande passante des téléchargements traverse le service.

**Ce qui la remettrait en cause** — un débit sortant qui dépasse ce qu'un nœud
peut servir : émettre alors une URL présignée **après** revérification, ou passer
par un CDN.
