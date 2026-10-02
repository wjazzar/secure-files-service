# Image du service — construite depuis les sources, sans rien présumer du poste
#
#   docker compose --profile app up -d --build
#
# Deux étapes : la construction (JDK + Maven Wrapper) et l'exécution (JRE seul,
# utilisateur non privilégié). Les tests ne tournent pas ici : ils ont besoin
# de Docker (Testcontainers) et tournent sur le poste (./mvnw verify).
#
# Images épinglées par digest, comme le reste de l'infrastructure : une mise à
# jour est un changement explicite, pas une surprise du prochain build.

FROM eclipse-temurin:21-jdk-alpine@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159 AS build
WORKDIR /build

# Les dépendances d'abord : cette couche ne se reconstruit que si le pom change.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q package -DskipTests \
    && cp target/securefiles-*.jar /build/app.jar


FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555

RUN addgroup -S praxedo && adduser -S -G praxedo praxedo
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER praxedo

# 8080 : l'API. 8091 : l'actuator (métriques, sondes), jamais sur le port de
# l'API (audit S-08).
EXPOSE 8080 8091

# Le tas est dimensionné sur la mémoire du conteneur, pas sur celle de l'hôte.
# Rien ne dépend de sa taille pour les fichiers : tout est flux (règle B-1),
# ce que la preuve mémoire vérifie avec 256 Mo de tas.
# 60 % et non 75 % : sur le gabarit de 1 Gio, la JVM occupe ~300 Mio hors du
# tas (métadonnées, code compilé, piles, tampons). À 75 %, tas plein et hors
# tas dépassaient la limite du conteneur, qui l'aurait tué. À 60 % : ~615 Mo
# de tas, quatre fois le maximum observé en charge (docs/capacity-planning).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=10 \
    CMD wget -q -O /dev/null http://localhost:8091/actuator/health/readiness || exit 1
