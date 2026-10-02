# ADR-0008 — Authentification activable : v1 sans, v2 Keycloak

> **Révisé le 2026-09-28 (porteur du projet) : la v2 devient le mode par
> défaut** (`praxedo.security.mode=oidc`), avec des identifiants de client
> identiques par défaut côté service et côté realm local — le service démarre
> branché sur le Keycloak de `docker-compose.yml` sans rien régler, et avertit
> au démarrage qu'il utilise le secret de développement. La v1 reste à un
> réglage (`SECURITY_MODE=none`, `scripts/start.sh --no-auth`). Les tests du
> périmètre fichier s'exécutent en v1, explicitement ; ceux de la v2 ont les
> leurs. Le texte ci-dessous est la décision d'origine.

- **Statut** : **Remplacé par [ADR-0014](0014-authentification-toujours-exigee.md)** (29/09) — le mode `none` et l'utilisateur `anonymous` sont supprimés : l'authentification n'a plus de réglage
- **Date** : 2026-09-27
- **Décision du registre** : D-10
- **Exigences concernées** : EX-02, EX-05
- **Complété par** : [ADR-0012](0012-session-navigateur-client-confidentiel.md) — le navigateur se connecte par le service (client confidentiel, cookie `HttpOnly`) ; le jeton `Bearer` reste celui des systèmes tiers

## Contexte

Le cadrage juge l'authentification non indispensable. Le porteur du projet
l'ajoute quand même (v2), prévue au contrat dès la v1 (`security`, `401`,
cloisonnement par propriétaire). Le front v1 fonctionne sans authentification.

## Décision

`praxedo.security.mode` : `none` par défaut (tout appartient à `anonymous`),
`oidc` pour exiger un jeton Keycloak. Le jeton est vérifié par les clés publiées
du fournisseur, puis l'émetteur, l'audience (`praxedo-files-api`) et l'échéance.
Le `sub` **est** le propriétaire. Le fichier d'autrui répond `404`, jamais `403`.
Le lien de téléchargement signé et l'actuator restent ouverts. L'émetteur attendu
(vu du navigateur) et l'adresse des clés (vue du service) se règlent séparément.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| Imposer l'authentification | Non demandé par le cadrage, et bloquant pour le front v1 |
| Rôles et permissions | Retirés du contrat 1.3 : un espace de fichiers privé par utilisateur suffit |

## Conséquences

**Positives** — la v2 est livrée sans casser la v1 ; activer l'authentification
n'a changé que la résolution du propriétaire et la configuration de sécurité ;
vérifié contre le vrai realm.

**Négatives** — deux modes à tester ; le front doit encore se brancher sur
Keycloak (session front).

**Ce qui la remettrait en cause** — un besoin de partage entre utilisateurs, qui
demanderait des droits par fichier.
