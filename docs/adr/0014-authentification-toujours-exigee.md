# ADR-0014 — Authentification toujours exigée, sans mode anonyme

- **Statut** : Accepté — signé par le porteur du projet le 2026-10-01 ; décision du porteur du projet (29/09), rédigé par Claude (Opus 5.5)
- **Date** : 2026-09-29
- **Décision du registre** : D-10 (remplace [ADR-0008](0008-authentification-activable.md))
- **Exigences concernées** : EX-02, EX-05

## Contexte

ADR-0008 livrait l'authentification **activable** : `praxedo.security.mode`
valait `oidc` (Keycloak, le défaut depuis le 28/09) ou `none` — une chaîne
`permitAll`, et tous les fichiers appartenaient à un utilisateur fictif,
`anonymous`.

L'audit de sécurité ([`../32-audit-de-securite.md`](../32-audit-de-securite.md),
S-01) a relevé l'asymétrie : la seule variable `SECURITY_MODE=none` suffisait
à couper l'authentification, sans rien signaler au démarrage, alors que le
mode antivirus factice exige **en plus** le profil `capacity`. Deux
corrections étaient possibles : poser la même garde, ou retirer le mode. Le
porteur du projet a retiré le mode.

## Décision

L'authentification n'a **plus de réglage**. Toute requête sous `/api` — hors
les points d'entrée de la connexion eux-mêmes — exige une session de
navigateur ou un jeton `Bearer` vérifié, et le propriétaire d'un fichier est
toujours le `sub` que Keycloak a garanti. Il n'existe plus d'utilisateur
anonyme, sous aucun nom.

Retirés :

- côté service : la propriété `praxedo.security.mode` et la variable
  `SECURITY_MODE`, la chaîne `permitAll`, les conditions sur ce mode ;
  `OwnerId.ANONYMOUS`, et le repli de `CurrentOwner` sur `anonymous` — sans
  preuve vérifiée, il échoue ;
- au contrat (1.7) : l'exigence vide `{}` de la liste globale `security` ;
- dans les scripts : `--no-auth` et `-NoAuth` ;
- côté interface : le mode `VITE_AUTH_MODE=none` et son adaptateur anonyme,
  le script `dev:v1`, le mode « optionnel » des bouchons MSW. Le mode par
  défaut devient `session` : un build sans configuration exige la connexion.

Les tests s'authentifient tous. Un Keycloak simulé (`TestIdentityProvider` :
clés publiées par WireMock, jetons signés par le test) est branché sur chaque
contexte Spring ; les tests du périmètre fichier agissent comme `test-user`,
par le chemin de production : vérification de signature, d'émetteur,
d'audience et d'échéance, propriétaire tiré du `sub`.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| Garder `none` derrière un profil `local`, comme l'antivirus factice derrière `capacity` (T-02 de l'audit) | Deux verrous au lieu d'un, mais une configuration qui ouvre l'API à tous reste livrée, à tester et à documenter. L'antivirus factice a une raison d'être — mesurer le plafond du code sans le moteur ; l'accès anonyme n'en a plus depuis que l'interface passe par Keycloak |
| Garder `none` pour les seuls tests | Les tests du périmètre fichier ne prouveraient rien du chemin réel : c'est par le propriétaire que passe le cloisonnement, et c'est ce chemin qu'ils doivent traverser |

## Conséquences

**Positives** — une seule configuration de sécurité : ce qui est testé est ce
qui tourne. S-01 disparaît au lieu d'être gardé. Toute la suite exerce la
vérification de jeton et le cloisonnement par propriétaire, pas seulement les
deux classes qui leur sont dédiées. Un build de l'interface sans
configuration ne démarre plus en accès libre (la moitié « mode anonyme » de
S-12).

**Négatives** — Keycloak devient indispensable pour utiliser l'API, même en
local : la pile le démarre déjà par défaut, mais il n'y a plus de
démonstration « sans connexion ». Chaque contexte de test démarre un serveur
WireMock pour les clés. Dans une base locale existante, les fichiers déposés
en v1 appartiennent à `anonymous`, qu'aucun `sub` ne porte : ils deviennent
inaccessibles — ni migrés ni effacés. Le commentaire de la migration
`V2__stored_file.sql` mentionne encore `'anonymous'` : une migration appliquée
ne se modifie pas (sa somme de contrôle Flyway changerait).

**Ce qui la remettrait en cause** — un besoin de **dépôt sans compte** (un
tiers qui envoie un fichier sans identité) : ce serait une fonctionnalité à
part — lien de dépôt signé, à usage limité, audité, avec quotas — et non un
interrupteur global de l'authentification.
