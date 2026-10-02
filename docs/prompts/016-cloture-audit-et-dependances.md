# 016 — Clôture de l'audit de sécurité et analyse des dépendances

- **Date** : 2026-10-01
- **Outil** : Claude Opus 5.5 (Claude Code)
- **Objectif** : trancher et traiter, en une passe, les questions restées
  ouvertes avant la livraison
- **Phase du projet** : livraison — transverse (back, front, infrastructure,
  documentation)

## Prompts

> Fais une analyse finale de ce qui reste à trancher avant la livraison,
> sous forme de questions.

> Chaque question doit se suffire à elle-même : le problème, son enjeu, le
> coût de la correction et les options. Ne me renvoie pas aux identifiants de
> l'audit.

Puis les décisions du porteur du projet, question par question (extraits) :

> Q1 : un profil de test, donc oui, à corriger. Q2 : à corriger, avec un rôle
> pour chacun. Q3 : publier sur 127.0.0.1. Q4 : corriger comme proposé. Q5 :
> l'antivirus est hors périmètre ; l'indiquer dans le README général, sans
> plus. Q6 : P2 à corriger ; P1 à expliquer, en attente. Q7, Q8 : à corriger.
> Q9 : supprimer toute la CI, c'est un exercice. Q10 : pas de CI/CD, mais un
> rapport d'analyse et des versions épinglées sur des versions stables. Q11 :
> le code se lance depuis l'IDE ou par le script ; fais le meilleur choix,
> sachant que l'on ne passe pas en production. Q13 : non, le bouchon suffit.
> Q14 : si la v7 est stable en production, la garder. Q15 : à corriger. Q17 :
> pas de suppression, pas de webhook ; fichier vide : à expliquer davantage.
> Q19 : finaliser le code, puis rejouer tous les commits depuis le début.
> Q20 : oui, pousser sur `main`.

## Le raisonnement

| Étape | Position | Ce qui l'a fait évoluer |
|---|---|---|
| 1. L'inventaire | Une première liste de questions renvoyait aux identifiants de l'audit (T-03, S-08…) | **Refusée par le porteur du projet** : chaque question doit se suffire à elle-même — problème, enjeu, coût, choix |
| 2. La CI | Rouge sur `main` depuis le premier jour : `mvnw` commité sans droit d'exécution | Trouvé en lisant les journaux d'exécution, pas en supposant ; corrigé, puis la CI retirée sur décision du porteur |
| 3. Les rôles PostgreSQL | Trois rôles, et **toute la suite de tests** tourne avec le rôle du service | Un test dédié prouve ce que le rôle ne peut pas faire ; le reste de la suite prouve qu'il suffit |
| 4. Le port de management | Prometheus visait `host.docker.internal:8080` | Avec les ports sur `127.0.0.1`, un conteneur ne joint plus le poste sous Linux : cible `backend:8091` par le réseau de compose |
| 5. `/actuator` sur le port de l'API | `404` attendu par l'audit | Mesuré : `401` sans jeton (la page d'erreur n'est pas publique), `404` avec. Meilleur encore : rien n'est révélé. Test et scripts alignés sur la mesure |
| 6. La recherche sans accents | `function('…')` en JPQL rendait un type inconnu, refusé par `like` | Forme typée `function('…' as String, …)` ; SQL généré vérifié identique à l'expression de l'index |
| 7. Les dépendances | OSV, NVD, OSS Index bloqués depuis l'environnement | Trivy, dont la base passe par un registre joignable : **3 failles critiques dans Tomcat**, dans le service lui-même, corrigées |
| 8. Les images | Épingler, oui ; monter de version ? | Correctifs de la même ligne seulement (Grafana, Prometheus), mesurés avant adoption ; Keycloak laissé à 26.4.7 et documenté |

## Ce que j'ai retenu

| Décision | Raison |
|---|---|
| Aucun identifiant par défaut ; valeurs de développement dans un profil **`local`** plutôt que `test` | Ce sont les valeurs de l'environnement compose, utilisées par l'IDE ; les tests, eux, fournissent les leurs, comme un déploiement |
| Les tests fournissent leurs identifiants de stockage par une classe séparée | Les enregistrer depuis la classe du conteneur l'aurait démarré pour des tests qui n'en ont pas besoin |
| `management.server.port` publié sur `127.0.0.1` | Les scripts lisent la santé sans détour ; aucune exposition hors du poste |
| Tomcat 11.0.26 et Jackson 3.1.7 surchargés au-dessus du BOM | Spring Boot 4.1.1 est la dernière version stable ; correctif de la même ligne seulement |
| Image Keycloak prise sur Docker Hub | `quay.io` injoignable ici ; même image officielle, empreinte vérifiable |
| React Router 7 conservé | Ligne maintenue (7.18.4 le 15/09/2026, le jour de la 8.4.0) |
| Interface servie par Vite (scripts ou IDE) | Pas de mise en production : une commande, multiplateforme, même origine que l'API |

## Ce que j'ai laissé, et pourquoi

| Point | Pourquoi |
|---|---|
| En-tête `Content-Security-Policy: sandbox` du téléchargement (S-18) | En attente : le porteur du projet a demandé une explication avant de décider |
| Montée de Keycloak sur sa ligne courante | Hors périmètre d'une clôture : documentée avec son déclencheur ([`33`](../33-analyse-des-dependances.md)) |
| CodeQL, gitleaks | Outils de chaîne continue ; pas de CI |
| Purge des fichiers infectés, droit à l'effacement, préparation TLS | Pistes, à compléter au README avec le porteur du projet |

## Vérifications effectuées

- Back-end, avec Docker : suite complète **verte** à chaque étape (référence
  480 tests ; 497 après les ajouts), plus les 2 tests mémoire.
- Front : `typecheck`, `lint`, `prettier`, 141 tests ; build de production
  inspecté (ni MSW, ni faux Keycloak, ni service worker) ; build refusé en
  mode simulé.
- Trivy sur le JAR, `package-lock.json` et chaque image ; `npm audit`.
- `docker compose config`, `bash -n` sur les scripts.
