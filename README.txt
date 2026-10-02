PRAXEDO - SERVICE DE FICHIERS SÉCURISÉS
Guide de démarrage
===========================================================================

Ce fichier explique comment tout démarrer sur un poste neuf : la base de
données, le stockage objet, Keycloak, l'antivirus, le service Spring Boot,
et l'interface React branchée sur le service.

Les choix, les hypothèses et la garantie de sécurité sont dans README.md.


---------------------------------------------------------------------------
1. EN BREF
---------------------------------------------------------------------------

  macOS, Linux, ou Windows avec Git Bash :

      ./scripts/start.sh

  Windows, depuis PowerShell ou l'invite de commandes :

      powershell -ExecutionPolicy Bypass -File scripts\start.ps1

  Puis ouvrir  http://127.0.0.1:5173

  Le script vérifie les prérequis, construit les images, démarre les
  conteneurs, attend qu'ils soient prêts, affiche les adresses, puis lance
  l'interface. Il se relance sans risque : ce qui tourne déjà est conservé.

  Premier lancement : compter 5 à 10 minutes (images Docker, dépendances
  Maven, dépendances npm, signatures de l'antivirus). Les suivants : moins
  d'une minute.

  Pour vérifier que tout fonctionne, dans un second terminal :

      ./scripts/check.sh
      powershell -ExecutionPolicy Bypass -File scripts\check.ps1   (Windows)

  Pour tout arrêter : Ctrl+C dans le terminal de l'interface, puis

      ./scripts/stop.sh
      powershell -ExecutionPolicy Bypass -File scripts\stop.ps1    (Windows)

  « -ExecutionPolicy Bypass » ne vaut que pour ce lancement : aucun réglage
  du poste n'est modifié.


---------------------------------------------------------------------------
2. PRÉREQUIS
---------------------------------------------------------------------------

  Obligatoire
    Docker Desktop, ou Docker Engine, avec Compose v2 (« docker compose »)
    4 Go de mémoire disponibles pour Docker : l'antivirus en prend 1 à 2
    Node.js 22.12 ou plus, avec npm - pour l'interface

  Pour les scripts bash : curl (présent sur macOS, Linux et Git Bash).

  Seulement pour lancer les tests, ou le service hors de Docker : JDK 21.

  Rien d'autre à installer : ni Maven (le service est compilé dans Docker,
  et le Maven Wrapper est fourni), ni PostgreSQL, ni Keycloak, ni ClamAV.

  Ports qui doivent être libres :
    5173 interface · 8080 et 8091 service · 8081 et 9001 Keycloak
    9000 antivirus · 5432 PostgreSQL · 8333 et 19333 stockage objet
    9090 Prometheus · 3000 Grafana
  (En cas de conflit, voir §8.)


---------------------------------------------------------------------------
3. LES SCRIPTS
---------------------------------------------------------------------------

  start, check et stop existent en bash (.sh) et en PowerShell (.ps1), avec
  les mêmes options ; demo.sh est en bash seulement. Tous se lancent depuis
  n'importe quel dossier.

  start     démarre tout, attend que tout soit prêt, lance l'interface
              (défaut)                 authentification Keycloak, toujours
                                       active (§6)
              --no-front   -NoFront    sans l'interface : l'API seule

  check     vérifie que chaque brique répond ET fait ce qu'elle promet :
              - PostgreSQL : les deux bases existent, le schéma est migré,
                un rôle par usage
              - stockage : la livraison ne peut PAS lire la quarantaine,
                et lit bien la zone servable (contre-épreuve)
              - antivirus : un contenu sain passe, EICAR est détecté
              - Keycloak : un jeton est délivré, avec la bonne audience
              - service : prêt ; refuse sans jeton ; jeton accepté
              - interface : servie, et branchée sur le service
            Rien de durable n'est écrit.

  stop      arrête les conteneurs ; les données sont conservées
              --purge      -Purge      efface aussi les données

  demo.sh   (bash seulement) le parcours de démonstration de l'API :
            un fichier sain déposé, analysé et relu à l'identique, puis
            EICAR bloqué et jamais servi
              --resilience   l'antivirus est arrêté en cours de route :
                             rien n'est perdu, aucun essai n'est consommé
              --big          un fichier de 500 Mo, aller et retour


---------------------------------------------------------------------------
4. CE QUE FAIT start, ÉTAPE PAR ÉTAPE - POUR LE REFAIRE À LA MAIN
---------------------------------------------------------------------------

  4.1 Les conteneurs - une seule commande, à la racine du dépôt

      docker compose --profile app up -d --build

      Compose démarre, dans l'ordre imposé par les dépendances :

      postgres           PostgreSQL 17. Deux bases : « praxedo » pour le
                         service, « keycloak » pour Keycloak (créée au
                         premier démarrage par infra/postgres/initdb/).
      objectstore        Stockage objet compatible S3 (SeaweedFS 4.47),
                         avec quatre identités aux droits distincts
                         (infra/seaweedfs/s3-identities.json).
      objectstore-init   Conteneur éphémère : crée les deux zones,
                         « quarantine » et « servable », puis s'arrête.
      keycloak           Keycloak 26.4. Le realm « praxedo » (clients et
                         comptes de démonstration) est importé au démarrage
                         depuis infra/keycloak/realm-praxedo.json.
      antivirus          ClamAV exposé par une API HTTP. Image dérivée
                         (infra/antivirus/), construite sur place ; elle
                         télécharge ses signatures au premier démarrage.
      backend            Le service Spring Boot, compilé dans Docker
                         (backend/Dockerfile). Au démarrage, Flyway crée le
                         schéma de la base : aucune migration à lancer.
      prometheus,        La supervision : Prometheus collecte les métriques
      grafana            du service, Grafana affiche le tableau de bord de
                         capacité, provisionné depuis infra/grafana/.

      Le profil « app » ajoute le service. Sans lui (« docker compose
      up -d »), seule l'infrastructure démarre, et le port 8080 reste libre
      pour lancer le service depuis un IDE (§7).

      Toute la configuration est versionnée : aucun fichier .env à créer.
      Les identifiants sont ceux du développement local (§5).

  4.2 Attendre que tout soit prêt

      docker compose --profile app ps

      Chaque service doit afficher « healthy ». L'antivirus reste
      « starting » une à deux minutes au premier démarrage, le temps de
      charger ses signatures : c'est normal. Le service, lui, ne l'attend
      pas : les dépôts sont acceptés tout de suite, et patientent dans la
      file jusqu'à ce que l'antivirus réponde.

  4.3 L'interface, branchée sur le service

      cd frontend
      npm ci
      npm run dev

      Le serveur de développement (Vite) sert l'interface sur
      http://127.0.0.1:5173 et relaie tout ce qui commence par /api vers
      le service, sur http://localhost:8080. L'interface et l'API
      partagent ainsi la même origine : pas de CORS à configurer, et les
      liens de téléchargement restent relatifs. C'est tout le couplage :
      aucune adresse n'est écrite dans le code du front.

      Si le service écoute sur un autre port :
        API_PROXY_TARGET=http://localhost:18080 npm run dev

  4.4 Vérifier

      ./scripts/check.sh


---------------------------------------------------------------------------
5. CHAQUE BRIQUE : ADRESSE, IDENTIFIANTS, VÉRIFICATION À LA MAIN
---------------------------------------------------------------------------

  Tous ces identifiants sont des identifiants de DÉVELOPPEMENT LOCAL,
  volontairement triviaux et versionnés pour que rien ne soit à configurer.
  Ils ne doivent jamais servir ailleurs. Tous les ports n'écoutent que sur
  la machine locale (127.0.0.1) : personne d'autre sur le réseau ne les
  atteint.

  Interface React     http://127.0.0.1:5173
      Déposer un fichier, suivre son analyse, le télécharger une fois
      déclaré sain. Liste paginée, recherche, filtre par statut, tri.

  Service (API)       http://localhost:8080
      Contrat           contracts/openapi.yaml
      Santé             http://localhost:8091/actuator/health
      Métriques         http://localhost:8091/actuator/prometheus
                        (port de management : le port de l'API, 8080, ne
                        sert ni santé ni métriques)
      Déposer un fichier en ligne de commande, avec le jeton d'un système
      tiers (variable $token : voir §6 - sans jeton, l'API répond 401) :
        curl -X POST http://localhost:8080/api/v1/files \
             -H "Authorization: Bearer $token" \
             -H "X-File-Name: rapport.pdf" \
             -H "Content-Type: application/octet-stream" \
             --data-binary @rapport.pdf
      Suivre : curl -H "Authorization: Bearer $token" \
                    http://localhost:8080/api/v1/files/<id>

  Supervision         http://localhost:3000   (Grafana, sans compte)
      Tableau de bord « Praxedo — capacité du service » : retard (fichiers,
      octets, âge du plus ancien, lag par fichier), débit, processeur et
      mémoire du nœud bridé à 1 CPU / 2 Go. Prometheus : localhost:9090.
      Essai de charge et dimensionnement : load/README.md.

  PostgreSQL          localhost:5432
      Administrateur : praxedo / praxedo — l'administration seulement
      Un rôle par usage, aucun superutilisateur pour les applications :
        praxedo_owner / praxedo-owner-local   migrations (Flyway)
        praxedo_app   / praxedo-app-local     le service : lignes seulement
        keycloak      / keycloak-local        sa propre base, rien d'autre
      Bases : praxedo (le service), keycloak (Keycloak)
      Console : docker exec -it praxedo-postgres psql -U praxedo -d praxedo
      Exemple : select status, count(*) from stored_file group by status;

  Stockage objet      http://localhost:8333 (API S3)
                      http://localhost:19333 (console d'administration)
      Zones : quarantine (fichiers non validés), servable (fichiers sains)
      Identités (clé / secret) :
        praxedo-ingest   / praxedo-ingest-secret     écrit la quarantaine
        praxedo-worker   / praxedo-worker-secret     analyse et promotion
        praxedo-delivery / praxedo-delivery-secret   lit la zone servable,
                                                     ne voit PAS la quarantaine
        praxedo-admin    / praxedo-admin-secret      administration

  Keycloak            http://localhost:8081
      Console d'administration : admin / admin
      Realm « praxedo » ; comptes de démonstration : ceux que déclare
      infra/keycloak/realm-praxedo.json (section « users »), avec leur mot
      de passe (il ne se saisit que sur la page de Keycloak)
      Jeton d'un système tiers (client confidentiel praxedo-integration) :
        curl -u praxedo-integration:praxedo-local-integration-secret-do-not-reuse \
             http://localhost:8081/realms/praxedo/protocol/openid-connect/token \
             -d grant_type=client_credentials

  Antivirus           http://localhost:9000
      Moteur et version des signatures :
        curl http://localhost:9000/version
      Analyser un contenu (200 = sain, 406 = menace) :
        printf 'bonjour' | curl -i --data-binary @- http://localhost:9000/scanHandlerBody
      Le test EICAR (la signature de test que tout antivirus reconnaît) est
      fait par check et par demo.sh : la chaîne y est assemblée en mémoire
      et n'est jamais écrite sur le disque - l'antivirus du poste mettrait
      le fichier en quarantaine.

  Vérifications plus poussées (isolation du stockage commande par commande,
  limites de l'antivirus) : infra/README.md §6.


---------------------------------------------------------------------------
6. AUTHENTIFICATION PAR KEYCLOAK
---------------------------------------------------------------------------

  Le service EXIGE toujours une authentification Keycloak : aucune option,
  aucune variable ne la coupe (docs/adr/0014). Le client praxedo-web et son
  secret de développement sont les mêmes par défaut dans le service et dans
  le realm importé : rien à régler (le service le signale au démarrage).

  Chaque utilisateur a son propre espace de fichiers : un fichier déposé
  par un utilisateur répond 404 à tout autre, comme un fichier inconnu.

  Dans le navigateur : « Se connecter » mène à la page de Keycloak, qui
  renvoie dans l'interface. Le service est le client CONFIDENTIEL de
  Keycloak (identifiant + secret) : il garde les jetons dans sa session,
  rangée dans PostgreSQL (Spring Session), et le navigateur ne reçoit qu'un
  cookie HttpOnly (docs/adr/0012). « Se déconnecter » passe par la page de
  fin de session de Keycloak, qui ramène à l'interface : la connexion
  suivante redemande le mot de passe.

  Pour un système tiers, par l'API avec un jeton :

      token=$(curl -s -u praxedo-integration:praxedo-local-integration-secret-do-not-reuse \
        http://localhost:8081/realms/praxedo/protocol/openid-connect/token \
        -d grant_type=client_credentials \
        | sed 's/.*"access_token":"\([^"]*\)".*/\1/')
      curl -H "Authorization: Bearer $token" http://localhost:8080/api/v1/files

  Keycloak déjà démarré avec l'ancien realm (avant le 28/09) : le realm
  n'est réimporté que s'il n'existe plus - voir infra/README.md §4.

  Pour découvrir l'interface avec un écran de connexion sans Docker :
  « npm run dev:mock » (§7) simule le fournisseur d'identité, avec ses
  propres comptes.


---------------------------------------------------------------------------
7. AUTRES FAÇONS DE LANCER
---------------------------------------------------------------------------

  Le service depuis les sources (IDE, débogueur) :
      docker compose up -d                  l'infrastructure seule
      cd backend
      ./mvnw spring-boot:run                (Windows : mvnw.cmd spring-boot:run)
    Puis l'interface comme au §4.3.
    spring-boot:run active le profil « local », qui porte les identifiants
    de développement : le service n'en a aucun par défaut. Depuis l'IDE,
    activer ce profil (variable SPRING_PROFILES_ACTIVE=local, ou l'option
    « Active profiles » de la configuration de lancement).

  L'interface seule, sur des bouchons, sans Docker ni service :
      cd frontend
      npm ci
      npm run dev:mock
    L'API est simulée dans le navigateur (MSW), avec un écran de connexion
    et les comptes alice, bob, claire / demo.

  Les tests :
      cd backend  &&  ./mvnw verify
        Une partie des tests tourne contre les vrais PostgreSQL, SeaweedFS
        et ClamAV (Testcontainers : Docker doit tourner). Environ 3 minutes
        une fois les images en cache.
      cd frontend &&  npm test


---------------------------------------------------------------------------
8. EN CAS DE PROBLÈME
---------------------------------------------------------------------------

  « Docker ne répond pas »
      Démarrer Docker Desktop, attendre qu'il soit prêt, relancer start.

  Le service ne démarre pas : « role "praxedo_owner" does not exist »,
  ou « password authentication failed »
      Le volume PostgreSQL date d'avant le 01/10 : les rôles ne se créent
      qu'au premier démarrage, sur un volume vide. Une fois :
        ./scripts/stop.sh --purge      (ou docker compose down -v)
      puis relancer start.

  Le service ne démarre pas : « Could not resolve placeholder 'DB_PASSWORD' »
      Il est lancé sans identifiants, et c'est voulu : aucun n'a de valeur
      par défaut. Depuis l'IDE, activer le profil « local » (§7).

  « port is already allocated » - un port est déjà pris sur le poste
      Chaque port de l'infrastructure se déplace par une variable :
        POSTGRES_PORT  BACKEND_PORT  BACKEND_MANAGEMENT_PORT  KEYCLOAK_PORT
        KEYCLOAK_MGMT_PORT  ANTIVIRUS_PORT  S3_PORT  S3_UI_PORT
        PROMETHEUS_PORT  GRAFANA_PORT
      Exemple, si un PostgreSQL local occupe déjà le 5432 :
        POSTGRES_PORT=5433 ./scripts/start.sh
        $env:POSTGRES_PORT=5433; powershell -ExecutionPolicy Bypass -File scripts\start.ps1
      Les scripts en tiennent compte, y compris pour relier l'interface
      au service. Le port 5173 de l'interface, lui, est fixe.

  Windows : « An attempt was made to access a socket in a way forbidden
  by its access permissions » sur un port
      Le port est dans une plage réservée par Windows (Hyper-V, WSL) ; ces
      plages changent à chaque redémarrage. Pour les voir :
        netsh interface ipv4 show excludedportrange protocol=tcp
      Déplacer le port concerné comme ci-dessus, par exemple pour la
      console du stockage : $env:S3_UI_PORT=29333

  « Le port 5173 est déjà pris »
      Une interface tourne déjà, peut-être sur des bouchons (npm run
      dev:mock). check dit laquelle. L'arrêter (Ctrl+C dans son terminal),
      puis relancer start.

  L'antivirus reste « starting » plusieurs minutes
      Normal au premier démarrage (téléchargement des signatures). S'il
      finit « unhealthy » : la mémoire allouée à Docker est probablement
      insuffisante (Docker Desktop > Settings > Resources, 4 Go ou plus).
      Journaux : docker compose logs antivirus

  Des fichiers restent « en attente d'analyse »
      L'antivirus n'est pas encore prêt. Ils seront analysés dès qu'il
      répondra, sans rien refaire : aucune tentative n'est consommée
      pendant son absence.

  Windows : « l'exécution de scripts est désactivée sur ce système »
      Utiliser la forme « powershell -ExecutionPolicy Bypass -File ... »
      donnée plus haut.

  L'antivirus du poste signale EICAR pendant check ou demo.sh
      C'est la preuve que la chaîne de test fonctionne : elle n'est jamais
      écrite sur le disque, mais un antivirus qui inspecte le trafic local
      peut la voir passer.

  Tout reprendre de zéro
      ./scripts/stop.sh --purge
      ./scripts/start.sh

  Voir les journaux d'une brique
      docker compose logs -f backend      (ou postgres, keycloak, antivirus,
                                           objectstore)
