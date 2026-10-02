# ADR-0015 — Le domaine sans annotation de persistance : l'entité JPA est distincte de l'agrégat

- **Statut** : Accepté — signé par le porteur du projet le 2026-10-01 ; décision du porteur du projet (30/09, relecture du back-end, remarque R-003), rédigé par Claude (Opus 5.5)
- **Date** : 2026-09-30
- **Décision du registre** : — (complète [ADR-0003](0003-jpa-pour-lire-sql-pour-garantir.md), qui reste en vigueur)
- **Exigences concernées** : EX-03, EX-15

## Contexte

ADR-0003 a introduit JPA pour les lectures. Pour l'appliquer au plus court,
l'agrégat `StoredFile` **était** l'entité : `@Entity`, `@Version`,
`@JdbcTypeCode`, un constructeur sans argument pour Hibernate et un rappel
`@PostLoad` pour revérifier l'invariant au chargement.

La relecture guidée du code (R-003) a relevé trois conséquences :

- l'affirmation « domaine sans framework » était fausse : le domaine avait une
  dépendance de compilation vers `jakarta.persistence` et Hibernate, et la
  règle ArchUnit portait une exception pour l'autoriser ;
- Hibernate construisait l'agrégat par réflexion, **sans passer par son
  constructeur** : au chargement, l'invariant reposait sur un rappel, et non
  sur la construction ;
- les champs ne pouvaient pas être `final` : l'immuabilité ne tenait qu'à
  l'absence de *setter*.

## Décision

**Séparer le modèle de persistance de l'agrégat.**

- `domain.file.model.StoredFile` : classe `final`, champs `final` typés par
  les objets valeur, invariant vérifié par le constructeur, **aucune
  annotation**. Toute instance — créée, issue d'une transition ou relue —
  passe par ce constructeur.
- `infrastructure.persistence.file.entity.StoredFileEntity` : une colonne par
  champ, aucun comportement, aucun *setter*.
- `StoredFileEntityMapper` convertit dans les deux sens ; vers le domaine, il
  appelle le constructeur de l'agrégat, comme `StoredFileRowMapper` le fait
  déjà pour les lignes rendues par la file de travail.
- `StoredFileJpaRepository` étend `Repository` et non `JpaRepository` : aucune
  méthode `save`, `delete` ou `flush` héritée. Il est en lecture seule par
  construction ; l'unique insertion passe par `EntityManager.persist`.

Le build le vérifie : `LayeringRulesTest` interdit désormais `jakarta..` et
`org.hibernate..` dans le domaine, sans exception ; `CodeLayoutRulesTest`
exige que toute classe `@Entity` vive dans `persistence/*/entity` et se nomme
`…Entity`.

## Alternatives envisagées

| Alternative | Pourquoi écartée |
|---|---|
| Garder l'agrégat comme entité, en documentant l'exception | Défendable (les transitions renvoyaient une nouvelle instance, jamais gérée par Hibernate), mais l'invariant au chargement restait confié à un rappel, les champs restaient mutables, et le domaine dépendait d'un ORM |
| Renommer seulement la classe en `StoredFileEntity` | Trompeur : les services auraient continué à manipuler un objet de persistance comme s'il était l'agrégat |
| Abandonner JPA et tout lire en SQL | Retour à la V0, que ADR-0003 a écartée pour la lisibilité des lectures |

## Conséquences

**Positives** — le domaine se compile et se teste sans aucune bibliothèque ;
l'invariant est vérifié par construction pour toute instance ; les deux
chemins de lecture (JPA et JDBC) passent par le même constructeur et ne
peuvent pas diverger sur ce qu'est un fichier valide ; le dépôt Spring Data ne
peut plus rien écrire.

**Négatives** — une classe miroir de 25 colonnes et un *mapper* à tenir
alignés sur le schéma. Le risque est couvert par
`StoredFileEntityMapperTest` (aller-retour dans chaque état, ligne incohérente
refusée à la lecture) et par les tests de persistance contre PostgreSQL.

**Ce qui la remettrait en cause** — rien de prévisible dans le périmètre :
c'est la forme attendue d'une architecture hexagonale. Si le coût du miroir
devenait gênant (schéma très mouvant), une projection par colonnes dans le
dépôt Spring Data serait l'étape suivante, pas le retour des annotations dans
le domaine.
