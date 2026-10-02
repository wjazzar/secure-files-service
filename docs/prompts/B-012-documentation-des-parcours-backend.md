# B-012 — Documentation du back-end par cas d'utilisation

- **Date** : 2026-09-27
- **Outil** : Codex (GPT-5)
- **Objectif** : rendre le code back-end lisible de bout en bout, par intention utilisateur et parcours technique
- **Phase du projet** : back-end, documentation technique

## Prompt

> Pour faciliter la relecture du back-end, écris une documentation par cas
> d'usage — le téléchargement, par exemple : les services impliqués, le flux
> de bout en bout, les états, les pannes et les garanties. Elle doit
> permettre de comprendre le code et servir de documentation technique.
> Périmètre : le back-end uniquement.

## Ce que j'ai retenu

- Un index d'entrée dans `backend/docs/`, puis un document autonome pour chacun
  des six ports d'entrée : dépôt, consultation, analyse/promotion,
  téléchargement, maintenance et supervision.
- Chaque document part du déclencheur réel, déroule les classes dans l'ordre,
  décrit les données et états touchés, les pannes, les garanties et termine par
  les tests qui matérialisent le parcours.
- Le téléchargement sépare le lien navigateur et l'accès API direct, puis
  montre leur convergence vers la même règle `StoredFile.isDownloadable()`.
- L'analyse distingue explicitement verdict antivirus `CLEAN` et état métier
  `AVAILABLE`, avec la promotion vérifiée entre les deux.
- La documentation pointe vers le code existant plutôt que de dupliquer
  l'architecture théorique.

## Ce que j'ai rejeté ou corrigé, et pourquoi

- Un document unique très long : il aurait rendu difficile la review d'un seul
  parcours et aurait vite mélangé les responsabilités.
- Une documentation limitée aux endpoints HTTP : le worker, le reaper et la
  supervision sont aussi des cas d'utilisation du cœur, pilotés par leurs
  propres adaptateurs.
- Des diagrammes ne montrant que les composants externes : la demande porte sur
  le code, donc les ports, services et adaptateurs concrets sont nommés.
- Présenter le verdict sain comme directement téléchargeable : le code impose
  d'abord `PROMOTING`, la copie, la vérification du SHA-256 et le CAS final.

## Vérifications effectuées

- Lecture des six ports d'entrée, de leurs services, contrôleurs ou
  planificateurs, adaptateurs JDBC/JPA/S3/antivirus/HMAC et de l'automate.
- Confrontation des parcours avec les migrations SQL, la configuration et les
  tests associés.
- Vérification automatique de tous les liens relatifs des huit documents :
  aucune cible manquante.
