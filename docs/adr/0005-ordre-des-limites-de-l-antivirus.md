# ADR-0005 — MaxFileSize strictement supérieur à MaxScanSize

- **Statut** : Accepté — signé par le porteur du projet le 2026-10-01 ; rédigé par Claude (Opus 5.5), session back-end
- **Date** : 2026-09-27
- **Décision du registre** : D-15
- **Exigences concernées** : EX-03, EX-07

## Contexte

Le spike B0 devait vérifier qu'un dépassement de limite ne remonte pas comme
une analyse propre. **Mesuré le 27/09** contre ClamAV 1.4.6, avec une signature
de sonde placée à la fin d'entrées d'archive : avec `MaxFileSize` 512M et
`MaxScanSize` 1024M, une entrée de 600 Mo ou de 1 100 Mo est **tronquée sans
alerte**, et l'archive déclarée saine. `AlertExceedsMax` ne lève rien sur ce
chemin ; la limite de volume cumulé, elle, alerte.

## Décision

`MAX_FILE_SIZE` = 2000M, **strictement supérieur** à `MAX_SCAN_SIZE` = 1024M :
c'est toujours la limite qui alerte qui cède la première. Le point d'entrée de
l'image dérivée **refuse de démarrer** si la relation est violée ou illisible.
`AntivirusEngineLimitsTest` le vérifie contre le vrai moteur à chaque build.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| Garder 512M et documenter | Un faux négatif connu et laissé en place |
| Découper les fichiers pour rester sous les limites | Interdit (règle 10) : une signature peut chevaucher deux morceaux |
| Relever toutes les limites au maximum | N'enlève pas la troncature, la déplace ; et désarme la protection contre les bombes de décompression |

## Conséquences

**Positives** — le faux négatif mesuré est fermé ; la règle est tenue par
l'image et par un test, pas par un commentaire.

**Négatives** — l'analyse d'une archive volumineuse coûte plus (le moteur lit
jusqu'à 1 Go décompressé) ; les tests correspondants durent environ deux minutes.
**Reste un angle mort du moteur**, indépendant de la configuration : une entrée
compressée derrière un en-tête local zip64 n'est pas analysée (EICAR y passe).
Il est caractérisé par un test et documenté en risque résiduel.

**Ce qui la remettrait en cause** — une version du moteur qui alerte aussi sur
la troncature par `MaxFileSize`, ou un changement de moteur.
