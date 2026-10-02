# B-008 — Les limites de l'antivirus, mesurées : un faux négatif trouvé et fermé

- **Date** : 2026-09-27
- **Outil** : Claude Opus 5.5
- **Objectif** : solder le point ⭐ du spike B0 — « un dépassement de limite remonte-t-il bien autre chose qu'une analyse propre ? »
- **Phase du projet** : back-end, finition (lot B0 resté ouvert)

## Prompt

> Avant de clore, solde le point resté ouvert au lot B0 : un dépassement des
> limites de l'antivirus doit remonter autre chose qu'une analyse propre.
> Mesure-le contre le vrai moteur ; ne le suppose pas.

Le plan (`backend/PLAN.md` §B0) portait ce point depuis le 26/09 avec la mention
« si elle répond `200`, la garantie est fausse et rien d'autre ne compte ».

## Ce qui a été mesuré

Première tentative, dans le test de bout en bout : des archives remplies de
zéros. Résultat : une archive contenant **une entrée de 600 Mo** revient
`AVAILABLE`, alors que 3 entrées de 400 Mo reviennent `UNSCANNABLE`.

Des zéros ne disent pas si la fin de l'entrée a été lue. D'où une **sonde** :
deux conteneurs du même moteur (configuration livrée / configuration corrigée),
une **signature maison** ajoutée à la base du moteur (`.ndb`, correspondance
n'importe où dans un fichier), et un marqueur placé **à la fin** des entrées.
Archives construites en mémoire, jamais écrites sur disque.

| Archive | `MaxFileSize` 512M (livré) | `MaxFileSize` 2000M |
|---|---|---|
| Entrée de 100 Mo + marqueur | détecté | détecté |
| Entrée de 600 Mo + marqueur | ❌ **`OK`** | détecté |
| Entrée de 1 100 Mo + marqueur | ❌ **`OK`** | `Heuristics.Limits.Exceeded.MaxScanSize` |
| 600 Mo de zéros, puis une entrée marqueur | détecté | détecté |
| 3 × 400 Mo | `Heuristics.Limits.Exceeded.MaxScanSize` | idem |
| EICAR, entrée compressée, en-tête local classique | détecté | détecté |
| EICAR, entrée compressée, **en-tête local zip64** (archive valide, 206 octets) | ❌ **`OK`** | ❌ **`OK`** |

## Ce que j'en ai conclu

| Constat | Décision |
|---|---|
| Une entrée plus grosse que `MaxFileSize` est **tronquée en silence** : la partie extraite passe sous la limite et est analysée normalement ; `AlertExceedsMax` ne lève rien sur ce chemin | **`MAX_FILE_SIZE` (2000M) strictement supérieur à `MAX_SCAN_SIZE` (1024M)** : c'est alors toujours la limite de volume — celle qui alerte — qui cède la première |
| Une règle de configuration qui n'est écrite que dans un commentaire finira par être violée | **Garde au démarrage** de l'image dérivée (`infra/antivirus/praxedo-entrypoint.sh`) : relation violée ou valeur illisible → refus de démarrer, code 64. Vérifié sur 512M/1024M, 1024M/1024M, valeur illisible |
| Une garantie non testée est une intention | `AntivirusEngineLimitsTest`, contre le **vrai moteur** avec la signature sonde : fin d'une entrée de 600 Mo lue ; 1 100 Mo et 3 × 400 Mo → non analysable. Il échoue avec l'ancienne configuration |
| L'angle mort zip64 est une limite **du moteur**, pas de la configuration | **Caractérisé** par un test (qui échouera le jour où le moteur la corrige), documenté comme risque résiduel avec ses parades possibles (politique d'archives côté service, second moteur) |

## Ce que j'ai rejeté

| Option | Pourquoi |
|---|---|
| Garder les tests « zéros seuls » dans le test de bout en bout | Ils ne prouvent pas que la fin a été lue ; le test avec marqueur les remplace |
| EICAR comme marqueur de fin d'entrée | Sa signature n'est reconnue qu'en **début** de fichier : elle ne peut pas répondre à la question posée |
| Découper les fichiers pour rester sous les limites | Interdit par la règle 10 de `AGENTS.md` : une signature peut chevaucher deux morceaux |
| Analyser les archives côté service pour fermer l'angle mort zip64 | Écrire son propre analyseur d'archives, c'est réintroduire exactement la surface d'attaque qu'on délègue à l'antivirus. Listé en piste, pas fait |
| Signaler l'angle mort en amont (ClamAV) | Action publique : laissée au porteur du projet |

## Vérifications effectuées

| Point | Résultat |
|---|---|
| Sonde manuelle, deux configurations, 9 + 4 archives | Tableau ci-dessus |
| Validité de l'archive zip64 | Extraite sans erreur par `zipfile` (Python) |
| Garde de démarrage | Refus sur les trois configurations fautives, démarrage sur 2000M/1024M |
| `AntivirusEngineLimitsTest` + test de bout en bout | 7 tests verts (~2 min, le moteur décompresse réellement) |
