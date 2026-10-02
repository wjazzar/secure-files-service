# ADR-0006 — Promotion par relecture vérifiée, sans zone temporaire

- **Statut** : Accepté — signé par le porteur du projet le 2026-10-01 ; rédigé par Claude (Opus 5.5), session back-end
- **Date** : 2026-09-27
- **Décision du registre** : D-02
- **Exigences concernées** : EX-03

## Contexte

Un fichier sain doit passer de la quarantaine à la zone servable. La copie
serveur-à-serveur (`CopyObject`) est rapide mais ne rend pas l'empreinte de ce
qu'elle a copié. Le plan prévoyait une zone temporaire (`servable/.tmp/…`) puis un
« renommage ».

## Décision

Le worker **relit** l'objet en quarantaine et l'écrit **directement sous sa clé
finale** dans la zone servable, en recalculant taille et SHA-256 au passage. Si
l'un des deux diffère de l'attestation, l'objet servable est supprimé et le
fichier retourne en file : jamais `AVAILABLE`. Sinon, `PROMOTING → AVAILABLE` par
écriture conditionnée au jeton de bail. Le **point de validation est la ligne** :
un objet servable n'est jamais servi tant que sa ligne n'est pas `AVAILABLE`
avec une attestation propre de sa propre empreinte.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| `CopyObject` | Pas d'empreinte : on promouvrait sans vérifier |
| Zone temporaire puis renommage | S3 n'a pas de renommage atomique ; la zone ne protégeait rien que la base ne protège déjà |

## Conséquences

**Positives** — ce qui est servi est prouvé identique à ce qui a été analysé ;
chaque point d'interruption converge (cinq cas testés).

**Négatives** — une lecture supplémentaire de la quarantaine par fichier sain.

**Ce qui la remettrait en cause** — un stockage capable de rendre une empreinte
vérifiable de la copie côté serveur.
