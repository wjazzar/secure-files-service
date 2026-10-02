# 014 — Suppression du mode sans authentification et de l'utilisateur `anonymous`

- **Date** : 2026-09-29
- **Outil** : Claude Opus 5.5 (Claude Code)
- **Objectif** : traiter le constat S-01 de l'audit de sécurité — non en gardant le mode `none` derrière un profil, mais en le supprimant partout
- **Phase du projet** : développement transverse (back, front, contrat, scripts, documentation) — ADR-0014, remplace ADR-0008

## Prompt

> Constat S-01 de l'audit : la variable `SECURITY_MODE=none` suffit à couper
> l'authentification, alors que le mode antivirus factice exige en plus le
> profil `capacity`. Plutôt que d'ajouter la même garde, supprime ce mode :
> plus de `SECURITY_MODE=none` ni d'utilisateur `anonymous`, nulle part —
> service, contrat, interface, scripts et documentation.

## Le raisonnement

| Étape | Position | Ce qui l'a fait évoluer |
|---|---|---|
| 1. Le constat | Asymétrie réelle : `ANTIVIRUS_MODE=instant-clean` exige aussi le profil `capacity`, `SECURITY_MODE=none` suffisait seul à ouvrir l'API à tous | Lecture de `AntivirusConfiguration` et de `SecurityConfiguration` |
| 2. La correction de l'audit (T-02) | Garder `none` derrière un profil `local` | **Écartée par le porteur du projet** : il ne veut plus du mode du tout, ni de l'utilisateur `anonymous` |
| 3. Le vrai coût | Toutes les suites d'intégration tournaient en `mode=none` : le supprimer oblige chaque test qui appelle l'API à présenter un vrai jeton | Lecture de `PostgresTestcontainer` |
| 4. La réponse | Un Keycloak simulé partagé (`TestIdentityProvider`) branché sur chaque contexte ; `HttpApi` porte le jeton de `test-user` par défaut ; les deux classes dédiées à l'authentification repartent sans identifiants | Leurs clés et leur WireMock, dupliqués, sont remplacés par ceux du support commun |

## Ce que j'ai retenu

| Décision | Raison |
|---|---|
| `praxedo.security.mode`, `SECURITY_MODE`, la chaîne `permitAll` et toutes les conditions sur ce mode retirés | Une configuration qui ouvre l'API à tous n'est plus livrée : il n'y a plus rien à garder |
| `OwnerId.ANONYMOUS` supprimé ; `CurrentOwner` **échoue** sans preuve vérifiée | Aucun propriétaire de repli : un repli fusionnerait les fichiers de tous ceux qui l'atteignent |
| Tests : jeton signé, vérifié par le chemin de production (JWKS, émetteur, audience, échéance) | Les tests du périmètre fichier traversent désormais le cloisonnement réel au lieu d'un raccourci |
| Contrat 1.7 : `{}` retiré de la liste globale `security` | Le contrat disait « accès anonyme permis » : il ne l'est plus |
| Front : plus de `VITE_AUTH_MODE=none` ni d'adaptateur anonyme ni de `dev:v1` ; `session` par défaut ; bouchons MSW qui exigent toujours un jeton ; état `unauthenticated` au lieu de `anonymous` | Un build sans configuration envoie à la connexion (moitié de S-12) ; le mot « anonyme » ne désigne plus rien |
| Scripts : `--no-auth` et `-NoAuth` retirés ; `check` compte comme **défaut** une liste servie sans jeton | Un service qui répondrait `200` sans identité serait une régression, plus un mode |

## Ce que j'ai rejeté ou laissé, et pourquoi

| Point | Pourquoi |
|---|---|
| Modifier le commentaire `'anonymous'` de `V2__stored_file.sql` | Une migration appliquée ne se modifie pas : Flyway refuserait de démarrer sur toute base existante (somme de contrôle) |
| Migrer ou effacer les lignes `owner_id = 'anonymous'` d'une base locale | Destructeur, et non demandé : elles deviennent simplement inaccessibles |
| Réécrire l'historique : entrées du journal (B-009, F-001, 020…), métadonnées des campagnes de charge (`load/results`, qui enregistrent `SECURITY_MODE=oidc`) | Ce sont des traces datées de ce qui a été fait et mesuré ; les corriger falsifierait l'histoire |
| L'accès anonyme en lecture de **Grafana** (`GF_AUTH_ANONYMOUS_ENABLED`) | Un autre système — l'outil de supervision local — et non un utilisateur du service ; signalé au porteur du projet, laissé en l'état |
| Renommer `AnonymousAuthenticationFilter` | Classe de Spring Security : seul notre commentaire autour a été reformulé |

## Vérifications effectuées

- Back : `./mvnw test` — **461 tests + 2 tests mémoire, 0 échec** ; après le
  passage du contrat en 1.7, `ContractConformanceTest` et `OidcSecurityTest`
  relancés (27 tests, verts).
- Front : `typecheck`, `lint`, `prettier --check`, **119 tests** verts ; test
  de dérive du contrat vert.
- Interface en mode bouchon, dans le navigateur : `/` renvoie à `/login` ; une
  fois Alice connectée, ses 9 fichiers et eux seuls ; pied de page raccourci
  après avoir constaté qu'il passait sur deux lignes.
- `docker compose config`, `bash -n` sur les scripts, analyse syntaxique des
  scripts PowerShell.
- Recherche des traces restantes (`SECURITY_MODE`, `security.mode`,
  `anonymous`, `no-auth`, `dev:v1`) : ne restent que les éléments listés
  ci-dessus, volontairement.
