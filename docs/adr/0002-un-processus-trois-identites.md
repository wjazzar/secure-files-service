# ADR-0002 — Un seul processus, trois identités de stockage

- **Statut** : Accepté — signé par le porteur du projet le 2026-10-01 ; rédigé par Claude (Opus 5.5), session back-end
- **Date** : 2026-09-27
- **Décision du registre** : D-02, D-16
- **Exigences concernées** : EX-03, EX-11

## Contexte

L'analyse initiale prévoyait trois rôles d'exécution (`ingest`, `delivery`,
`worker`), déployables séparément, pour que le chemin de téléchargement ne
puisse matériellement pas lire la quarantaine. Le porteur du projet a refusé de
démarrer trois processus pour une application de cette taille (audit du 27/09),
et d'éclater le build en modules Maven.

## Décision

**Un seul processus et un seul module Maven.** La séparation vit à deux
endroits : dans le **code** (un port de stockage par rôle — `QuarantineWriter`,
`ServableReader`, `WorkerStorage` — et des couches dont les dépendances sont
vérifiées par ArchUnit), et surtout dans le **stockage** : trois clients S3,
trois identités. Celle du téléchargement n'a **aucun** droit sur la quarantaine ;
celle du dépôt n'a que l'écriture. Un test le vérifie à chaque build, contre un
vrai SeaweedFS.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| Trois processus | Trois déploiements pour un service de cette taille ; la garantie réelle vient des identités de stockage, qui restent |
| Multi-module Maven | La frontière passerait par le classpath ; ArchUnit la tient aussi bien dans un seul module, pour un build plus simple |

## Conséquences

**Positives** — un seul artefact, un seul démarrage ; l'isolation reste une
propriété du stockage, pas du code : même un bug de code ne peut pas faire lire
la quarantaine au téléchargement.

**Négatives** — un nœud porte les trois rôles : une analyse lourde partage la
machine avec les téléchargements (tempéré par le nombre fixe de boucles d'analyse) ; un
processus compromis détient les trois identités.

**Ce qui la remettrait en cause** — un besoin de dimensionner séparément
l'analyse et la livraison, ou une exigence de réduire ce qu'un processus
compromis peut atteindre : la découpe en rôles reste possible sans changer le
code, les ports existent déjà.
