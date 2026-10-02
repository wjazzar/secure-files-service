# 017 — Revue complète du code, puis corrections

- **Date** : 2026-10-01
- **Outil** : Claude Opus 5.5 (Claude Code), dont deux sous-agents de revue
- **Objectif** : chercher le code mort, le code inutilisé et les risques de
  sécurité dans tout le back-end et tout le front-end, puis tout corriger
- **Phase du projet** : livraison — transverse (back, front, contrat, infrastructure, documentation)

## Prompts

> Pendant que je relis le journal des prompts, lance une revue complète du code
> et des fichiers, back-end et front-end, par des sous-agents : code mort, code
> inutilisé, risques de sécurité. Chaque constat doit être vérifié, avec sa
> preuve.

> Réalise l'ensemble des corrections nécessaires. Nettoie tout, y compris les
> variables CSS obsolètes. Une fois terminé, commite et pousse.

## Le raisonnement

| Étape | Position | Ce qui l'a fait évoluer |
|---|---|---|
| 1. La revue | Deux sous-agents en parallèle, en lecture seule, chacun sur sa moitié ; consigne : ne rien signaler sans preuve (graphe de références tiré du bytecode, `knip`, sondes exécutées dans un dossier temporaire) | Un constat non vérifié coûte une correction inutile |
| 2. Le tri | Trois constats moyens (délais vers PostgreSQL, balayage de la quarantaine, déconnexion annoncée à tort), une dizaine de faibles, un nettoyage | Les trois moyens revérifiés à la main dans le code avant d'être présentés |
| 3. Les corrections | Une correction par commit, chacune avec le test qui la prouve ; les décisions déjà prises (pas de CI, pas de Playwright, antivirus hors périmètre) ne sont pas rouvertes | — |

## Ce que j'ai retenu

| Correction | Raison |
|---|---|
| Délais vers PostgreSQL : connexion, requête (annulée par le serveur), socket | Le délai du pool ne bornait que l'attente d'une connexion libre ; le pilote attend sans fin par défaut. Le test relit `statement_timeout` **sur le serveur**, pour chaque pool |
| Balayage repris après la dernière clé | Les fichiers bloqués, gardés comme preuve, revenaient en tête de chaque passage et masquaient les orphelins |
| Déconnexion confirmée par le service seulement | Annoncer « déconnecté » quand la session reste ouverte est faux, et dangereux sur un poste partagé |
| États des requêtes SQL dérivés de l'automate, écrits en littéraux | Un paramètre empêcherait PostgreSQL d'utiliser les index partiels ; un test lie la migration des index aux mêmes ensembles |
| Délais de maintenance liés au dépôt le plus lent, vérifiés au démarrage | Une configuration incohérente ne démarre pas, au lieu d'échouer sur un gros fichier |
| Aucun code du mode bouchon dans un build | Formulaire, comptes et libellés de démonstration chargés à la demande, hors build ; garde indépendante du mode et du dossier |
| Code de raison jamais produit retiré du contrat (1.10) | Un code publié doit pouvoir être reçu |
| Lecture tolérante du code de raison, identique en JPA et en JDBC | Les deux chemins de lecture ne doivent pas diverger |
| Identité `worker` sans lecture ni liste sur la zone servable | Moindre privilège : elle y écrit, elle n'y relit jamais |

## Ce que j'ai rejeté ou laissé, et pourquoi

| Point | Pourquoi |
|---|---|
| Lire le `Retry-After` de suivi dans l'interface | Choix documenté : l'interface interroge la liste à son rythme ; l'en-tête sert les clients de l'API |
| Contrôle CSRF dans les bouchons | Les bouchons authentifient par jeton `Bearer`, qui n'est pas porté par le navigateur : le CSRF ne s'y applique pas |
| Fusionner les libellés de même texte | Contextes différents (en-tête de colonne, champ du détail) : un catalogue de libellés les garde séparés |
| Retirer `src/lib/utils.ts` | Cible de l'alias `utils` de shadcn : le générateur de composants en dépend ; documentée comme telle |
| Sous-composants shadcn inutilisés | Code généré, gardé entier pour rester conforme à sa source |
| Double liaison de `praxedo.security.oidc` | Choix documenté : deux adaptateurs partagent des clés de configuration, pas des classes |
| Ancres de `docs/32` | Elles décrivent le code au moment de l'audit ; celles des scénarios sont recalées |

## Vérifications effectuées

- Back-end : 517 tests et les 2 tests mémoire, contre PostgreSQL et le
  stockage objet réels.
- Front-end : lint, types, format, 159 tests ; `npm run build`, puis aucune
  chaîne du mode bouchon dans `dist/`, y compris avec `--mode session` vers un
  autre dossier ; `--mode mock` refusé.
- Les 234 ancres de ligne des scénarios recalées sur le code (version du code
  au moment où chaque ligne de doc a été écrite, puis correspondance avec le
  code actuel), et contrôlées : aucune ancre vide, aucune plage inversée,
  chaque méthode citée trouvée à son ancre.
