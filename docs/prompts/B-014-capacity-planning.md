# B-014 — Capacity planning avec antivirus réel et simulé

- **Date** : 2026-09-28
- **Outil** : Codex
- **Objectif** : mesurer ce que traite un nœud back-end de 1 CPU / 1 Gio, séparer le coût de l'antivirus externe du plafond du code et conserver un protocole reproductible
- **Phase du projet** : back-end, charge et dimensionnement

## Prompt

> Mesure la capacité du back-end : d'abord avec l'antivirus réel dans
> Docker, puis avec une réponse d'antivirus simulée, pour isoler le plafond
> du code, puisque l'antivirus devient le goulot. Conserve les scripts et
> leurs environnements dans `load/`, documente la méthode et les résultats
> dans `docs/capacity-planning/`, et conclus sur le nombre de traitements
> simultanés qu'un nœud tient.

## Ce que j'ai retenu

| Décision | Raison |
|---|---|
| Deux campagnes : ClamAV HTTP réel et adaptateur `instant-clean` | La différence isole le coût de l'antivirus sans masquer le stockage, le SHA-256, la base ni la promotion |
| Le simulateur consomme tout le flux avant `CLEAN` | Un bouchon qui répondrait sans lire mesurerait un autre code et donnerait un plafond artificiel |
| Nœud back-end limité à 1 CPU / 1 Gio ; dépendances séparées | ClamAV dépasse à lui seul 1 Gio : le Compose complet ne tient pas sur une machine de 1 Gio |
| 50 dépôts simultanés de 2 Mio avant chaque mesure de débit | Répondre séparément à la concurrence d'entrée et au débit asynchrone du pipeline |
| Échauffement puis trois confirmations sur volumes neufs, médiane retenue | Éviter qu'un démarrage froid, une file héritée ou un meilleur passage isolé devienne le chiffre annoncé |
| Résultats bruts conservés sous `load/results/` | Chaque affirmation du rapport reste vérifiable et rejouable |

## Ce que les essais ont appris

- Le démarrage à froid a produit des paliers non monotones. Le profil réel a
  été confirmé après 30 s à 30 fichiers/s ; le profil simulé, plus rapide,
  demande 60 s à 80 fichiers/s.
- Avec ClamAV réel, 80 fichiers/s tient sur le corpus aléatoire ; la cible de
  dimensionnement reste 50 fichiers/s pour conserver une marge.
- Sans le temps de ClamAV, 120 fichiers/s tient et 150 fichiers/s déclenche la
  contre-pression : l'antivirus est un coût important, mais le code, le CPU et
  le stockage ont eux aussi un plafond.
- Les rafales de 50 fichiers de 2 Mio sont acceptées sans rejet ; la durée p95
  inclut le transfert complet du contenu.
- L'invariant « aucun fichier non scanné servi » est resté à zéro.
- Une saturation sur un état ancien a exposé une erreur de rejeu d'un corps S3
  en flux, susceptible de laisser un fichier en `PROMOTING` jusqu'au reaper.
  Le résultat de capacité est donc accompagné de cette limite, pas présenté
  comme un engagement de production.

## Livrables

- `load/run-capacity.mjs`, `load/k6/concurrency.js`, environnements et preuves ;
- mode `praxedo.antivirus.mode=instant-clean` et son test unitaire ;
- [`../capacity-planning/README.md`](../capacity-planning/README.md).
