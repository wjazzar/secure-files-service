# B-009 — Finition et livraison : observabilité, Keycloak, audit, README

- **Date** : 2026-09-27
- **Outil** : Claude Opus 5.5
- **Objectif** : terminer tous les lots restants (B7 à B9) et livrer
- **Phase du projet** : back-end, finition

## Prompt

> Termine les lots restants (B7 à B9) — observabilité, authentification,
> journal d'audit, README — et livre un ensemble qui démarre en une commande.

Puis, après une coupure de session : « Reprends là où tu t'étais arrêté. »

## Ce que j'ai retenu

| Décision | Raison |
|---|---|
| **Métriques de l'antivirus par un décorateur** du port | La couche application reste sans framework ; ni l'adaptateur ni le worker ne savent que les métriques existent |
| **Jauges calculées à la lecture**, `NaN` si la base ne répond pas | Une jauge en retard ment pendant l'incident qu'elle doit révéler ; un zéro par défaut rassurerait à tort |
| **L'invariant dans la santé globale, pas dans la disponibilité** | Tous les nœuds lisent la même base : un échec de disponibilité retirerait tout le service de la rotation, fichiers sains compris. L'alarme sonne, la décision reste humaine |
| **Service dans un profil compose `app`** | `docker compose up` seul laisse le port 8080 au développement ; `--profile app` lance tout |
| **Le service n'attend pas l'antivirus** au démarrage | La résilience annoncée est visible dès le premier lancement |
| **Keycloak activable** (`SECURITY_MODE=oidc`), désactivé par défaut | Le cadrage ne l'exige pas ; le front v1 n'en a pas ; la v2 est livrée sans rien casser |
| **Émetteur et adresse des clés réglés séparément** | Derrière Docker, le navigateur voit `localhost:8081`, le service `keycloak:8080` : les confondre fait refuser tous les jetons |
| **`CurrentOwner` échoue plutôt que de retomber sur `anonymous`** en mode `oidc` | Un repli silencieux fusionnerait les fichiers de tout le monde |
| **Journal d'audit : trigger pour les transitions, application pour les téléchargements** | Le plan (lot B3) prévoyait un `JdbcAuditLog` jamais écrit — trou trouvé en relisant l'architecture. Par trigger, aucun chemin de code ne peut changer un statut sans trace |
| **Ajout seul par triggers**, pas par `REVOKE` | En local, un seul rôle possède les tables : un `REVOKE` ne l'empêcherait de rien |
| **ADR au statut *Proposé*** | Règle du projet : les ADR sont signés par le porteur du projet |

## Ce que j'ai corrigé en chemin

| Écart | Correction |
|---|---|
| Le contrôle de santé du stockage dans `docker compose` échouait **toujours** depuis la montée en SeaweedFS 4.47 : `localhost` y résout en `::1`, le serveur n'écoute qu'en IPv4 | Contrôle sur `127.0.0.1`. Invisible dans les tests (Testcontainers attend depuis l'hôte) : trouvé en lançant la pile complète |
| `ARCHITECTURE.md` décrivait encore des ports disparus (`FileContentStore`, `capabilities()`), la zone `.tmp` de promotion, un refus de démarrer sans secret, `416` pour plusieurs plages | Aligné sur le code livré |
| Deux métriques prévues non livrées (`promotion.failures{stage}`, `storage.operation.duration`) | Remplacement documenté pour la première, piste pour la seconde |

## Ce que j'ai rejeté

| Option | Pourquoi |
|---|---|
| Rendre l'authentification obligatoire | Non demandé par le cadrage, bloquant pour le front v1 |
| Modifier le contrat (`security`) | Le contrat décrit déjà la v1 et la v2 ; le mode se choisit au déploiement |
| Télécharger un fichier sur le disque pendant la vérification du front | Vérifié par des requêtes de page (lien, plage de 26 octets) plutôt qu'en déposant un fichier chez l'utilisateur |
| Toucher aux fichiers du front | Propriété de la session front : seule une entrée de lancement « vraie API » a été ajoutée à `.claude/launch.json` |

## Vérifications effectuées

| Point | Résultat |
|---|---|
| Pile complète en conteneurs + `scripts/demo.sh --resilience --big` | Sain relu à l'identique ; EICAR `409 FILE_INFECTED` ; antivirus arrêté → dépôt accepté, **0 essai consommé**, analysé au redémarrage ; 500 Mo aller-retour identiques |
| Mode `oidc` contre le **vrai realm Keycloak** | `401 UNAUTHENTICATED` sans jeton ; Bob ne voit rien des fichiers d'Alice (liste vide, `404`) ; propriétaire en base = `sub` du jeton |
| **Front sur le vrai service** (proxy Vite) | Liste, compteurs, filtre multi-statuts ; lien `201`, plage `206`, `attachment` + `nosniff`, `409` sur le fichier infecté |
| Journal d'audit | Cycle de vie complet, verdict avec ses preuves, bail expiré attribué au *reaper*, `UPDATE`/`DELETE`/`TRUNCATE` refusés, audit qui survit à la purge |
| Suite complète | **381 tests verts**, plus les deux preuves mémoire |
