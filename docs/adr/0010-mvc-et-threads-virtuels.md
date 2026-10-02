# ADR-0010 — Spring MVC et threads virtuels, bornes explicites

- **Statut** : Accepté — signé par le porteur du projet le 2026-10-01 ; rédigé par Claude (Opus 5.5), session back-end
- **Date** : 2026-09-27 (révisé le 2026-09-30 : sémaphore d'analyse retiré, la borne des analyses est le nombre de boucles)
- **Décision du registre** : —
- **Exigences concernées** : EX-06, EX-07

## Contexte

Le service est bloquant par nature : il relaie des flux vers le stockage et vers
l'antivirus. Il doit tenir de nombreux utilisateurs simultanés et des fichiers
de 500 Mo sans mémoire proportionnelle.

Avec les threads virtuels, la taille d'un pool n'est plus une limite pour des
tâches **soumises** : chaque requête HTTP reçoit son propre thread.

## Décision

Spring MVC, threads virtuels activés, corps lus et écrits **en flux** (tampon de
64 Kio), multipart désactivé. Preuve : 500 Mo déposés, promus et téléchargés à
travers un tas de 256 Mo, dans une exécution de test dédiée.

La concurrence est bornée **explicitement, une fois par ressource, là où rien
d'autre ne la borne** :

- **les dépôts** arrivent un thread par requête : un `Semaphore` pris sans
  attendre les limite par nœud (`429` au-delà) ;
- **les analyses** ne sont pas soumises, elles sont **tirées** par un nombre
  fixe de boucles (`praxedo.worker.concurrency`), dont chacune attend la fin de
  son analyse avant de prendre le fichier suivant. Ce nombre est la borne des
  appels simultanés à l'antivirus ; un pic de dépôts allonge la file, il
  n'ajoute aucune analyse. `ScanWorkerPoolTest` la tient.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| WebFlux | Un modèle réactif pour un service bloquant par nature, plus difficile à lire et à défendre, sans gain une fois les threads virtuels disponibles |
| Pool de threads comme limite des dépôts | Faux avec les threads virtuels |
| `Semaphore` devant l'appel antivirus, **en plus** des boucles (retenu d'abord, retiré le 30/09) | Au même nombre de permis que de boucles, il ne pouvait jamais bloquer : une garantie en double qu'aucun test ne peut exercer. Et il était mal placé : pris après le claim, une attente aurait consommé le bail du fichier sans rien lire. Une borne propre au service ne se justifierait que si plusieurs chemins appelaient l'analyse — et elle se prendrait alors **avant** le claim, sans attendre |

## Conséquences

**Positives** — code impératif lisible ; mémoire constante quelle que soit la
taille du fichier, **mesurée** ; une borne par ressource, dont on sait dire
pourquoi les threads virtuels ne l'annulent pas.

**Négatives** — les bibliothèques qui épinglent le thread porteur (blocs
`synchronized` longs) restent à surveiller. La borne des analyses repose sur un
contrat : le service ne se protège pas lui-même, c'est l'adaptateur qui
l'appelle qui en fixe le nombre d'appels simultanés (écrit sur le port
`ScanFilesUseCase`).

**Ce qui la remettrait en cause** — un profil de charge dominé par des milliers
de connexions lentes où le coût par requête deviendrait significatif ; pour la
borne des analyses, un second chemin qui déclencherait une analyse (analyse à
la demande, travail soumis à un exécuteur) : il faudrait alors une borne dans
le service, prise avant le claim.
