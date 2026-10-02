# Énoncé du test technique — résumé

> **Source de vérité des exigences.** Toute interprétation, hypothèse ou
> décision doit pouvoir se rattacher à une exigence `EX-xx` de ce document.
>
> **Résumé, pas reproduction** (décision `D-17`) : le dépôt est public, et
> publier mot pour mot l'énoncé d'un test de recrutement l'exposerait à
> l'indexation. Ce document en garde le fond et la traçabilité ; les
> extraits cités dans le tableau des exigences sont limités à quelques mots.

---

## Résumé de l'énoncé

**Contexte.** Une entreprise met à disposition de ses clients des fichiers de
natures diverses. Aucun ne doit être téléchargeable avant d'avoir été analysé
par un antivirus.

**Objectif.** Concevoir et développer un micro-service de fichiers sécurisés
qui :

- reçoit et conserve des fichiers envoyés par des utilisateurs ou des
  systèmes tiers ;
- garantit qu'aucun fichier n'est servi sans analyse antivirus préalable ;
- permet de télécharger les fichiers validés par une API.

Le service doit tenir **de nombreux utilisateurs simultanés** et des fichiers
de **tailles très variables**.

**Contrainte technique.** Écosystème **Java / Spring Boot / React**.

**Livrables.**

- Le code d'une application fonctionnelle sur un dépôt Git public, qui reçoit
  et sert des fichiers et délègue leur analyse à un antivirus joignable par
  une API.
- Un README : choix techniques et d'architecture, hypothèses, pistes
  d'amélioration.
- Les prompts d'IA utilisés, stockés dans le dépôt (l'usage de l'IA est
  encouragé).

**Consignes.** Les points ambigus ou incomplets se traitent par des
hypothèses documentées ; des précisions peuvent être demandées.

**Suite.** La solution sert de base à un échange technique : présenter
ses choix, en discuter les compromis, explorer les évolutions. Il n'y a pas de
réponse unique ; le raisonnement, la démarche et la capacité à défendre ses
décisions comptent davantage.

---

## Extraction des exigences (traçabilité)

Identifiants utilisés dans le reste de la documentation pour tracer chaque
exigence jusqu'à son implémentation et son test.

| ID | Exigence | Type | Extrait de l'énoncé |
|---|---|---|---|
| **EX-01** | Recevoir et conserver des fichiers | Fonctionnelle | « Recevoir et conserver des fichiers transmis… » |
| **EX-02** | Émetteurs : utilisateurs **et** systèmes tiers | Fonctionnelle | « …par des utilisateurs ou des systèmes tiers » |
| **EX-03** | Aucun fichier servi sans scan préalable | **Sécurité — invariant** | « Garantir qu'aucun fichier n'est servi… » |
| **EX-04** | Téléchargement des fichiers validés | Fonctionnelle | « Permettre le téléchargement des fichiers validés » |
| **EX-05** | Interface **programmatique** (API) | Fonctionnelle | « …via une interface programmatique » |
| **EX-06** | Supporter de nombreux utilisateurs simultanés | Non fonctionnelle | « …nombreux utilisateurs simultanés » |
| **EX-07** | Manipuler des fichiers de **tailles très variables** | Non fonctionnelle | « …fichiers de tailles très variables » |
| **EX-08** | Antivirus **délégué**, joignable **via une API** | Architecture | « déléguer leur analyse à un antivirus disponible via une API » |
| **EX-09** | Écosystème Java / Spring Boot / React | Contrainte | « doit impérativement s'inscrire… » |
| **EX-10** | Dépôt Git **public** | Livrable | « hébergé sur un dépôt Git public » |
| **EX-11** | Application **fonctionnelle** | Livrable | « d'une application fonctionnelle » |
| **EX-12** | Document : choix, hypothèses, pistes d'amélioration | Livrable | « Un document (README ou équivalent)… » |
| **EX-13** | Prompts d'IA stockés dans le dépôt | Livrable | « stockiez dans le repo github du projet les prompts utilisés » |
| **EX-14** | Hypothèses formulées et documentées | Démarche | « formulez vos propres hypothèses et documentez-les » |
| **EX-15** | Capacité à défendre les compromis | Démarche | « discuter les compromis », « défendre vos décisions » |

## Ce que l'énoncé ne dit **pas** (silences significatifs)

Chacun a été traité soit par une précision obtenue le 21/09
([`28-precisions-de-cadrage.md`](28-precisions-de-cadrage.md)), soit par une
hypothèse documentée (README §9).

- Aucune indication de **volumétrie** (nombre de fichiers, débit, pic).
- Aucune borne sur « tailles très variables » (10 Ko ? 10 Go ?).
- Aucun **moteur antivirus** nommé. ⚠️ Ce n'est **pas** un silence : « un
  antivirus disponible via une API » signifie que **le choix est libre**. Le
  silence porte seulement sur les limites du moteur retenu, qui sont à établir
  soi-même.
- Aucune mention de **latence acceptable** entre l'upload et la disponibilité
  → rien n'impose un scan synchrone, rien ne l'interdit.
- Aucune mention d'**authentification**, d'**autorisation** ou de **multi-tenancy**.
- Aucune politique sur le **devenir des fichiers infectés**.
- Aucune mention de **rétention**, de **versioning**, ou de **suppression**.
- Aucune cible de **déploiement** (cloud, on-prem, Kubernetes).
- Aucune contrainte de **conformité** (RGPD, chiffrement au repos, audit).
- Aucun **budget temps** indiqué pour l'exercice.
