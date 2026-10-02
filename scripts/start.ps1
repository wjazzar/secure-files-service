<#
.SYNOPSIS
    Démarre tout, en une commande : base de données, stockage objet, Keycloak,
    antivirus, service Spring Boot, puis l'interface React branchée sur le service.

.DESCRIPTION
    powershell -ExecutionPolicy Bypass -File scripts\start.ps1           tout, avec l'authentification Keycloak -
                                                                         toujours active : connexion par le navigateur
                                                                         (cookie HttpOnly), jeton pour l'API
    powershell -ExecutionPolicy Bypass -File scripts\start.ps1 -NoFront  sans l'interface (l'API seule)

    Relancer le script est sans risque : ce qui tourne déjà est conservé.
    Ctrl+C arrête l'interface ; scripts\stop.ps1 arrête le reste.

    Prérequis : Docker avec Compose v2 ; Node 22.12+ pour l'interface.
    « -ExecutionPolicy Bypass » ne vaut que pour ce lancement : aucun réglage
    du poste n'est modifié. Équivalent bash : scripts/start.sh.
#>
param(
    [switch]$NoFront
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$front = -not $NoFront
$frontSkipped = $null
$backendPort = if ($env:BACKEND_PORT) { $env:BACKEND_PORT } else { '8080' }
$keycloakPort = if ($env:KEYCLOAK_PORT) { $env:KEYCLOAK_PORT } else { '8081' }
$antivirusPort = if ($env:ANTIVIRUS_PORT) { $env:ANTIVIRUS_PORT } else { '9000' }
$s3Port = if ($env:S3_PORT) { $env:S3_PORT } else { '8333' }
$s3UiPort = if ($env:S3_UI_PORT) { $env:S3_UI_PORT } else { '19333' }
$postgresPort = if ($env:POSTGRES_PORT) { $env:POSTGRES_PORT } else { '5432' }
$waitSeconds = if ($env:WAIT_SECONDS) { [int]$env:WAIT_SECONDS } else { 900 }

function Write-Step([string]$text) { Write-Host ''; Write-Host $text -ForegroundColor Cyan }
function Stop-WithError([string]$text) { Write-Host ''; Write-Host $text -ForegroundColor Red; exit 1 }

# Commandes natives. Sous Windows PowerShell 5.1, une ligne écrite sur stderr
# (Compose y écrit sa progression) devient une exception quand la sortie est
# redirigée et que $ErrorActionPreference vaut 'Stop' : la préférence est
# relâchée le temps de l'appel, et c'est le code de sortie qui fait foi.
function Invoke-Native([scriptblock]$command) {
    $ErrorActionPreference = 'Continue'
    & $command | Out-Host
    return $LASTEXITCODE
}
function Test-Native([scriptblock]$command) {
    $ErrorActionPreference = 'Continue'
    & $command *> $null
    return $LASTEXITCODE -eq 0
}
function Get-Health([string]$container) {
    # État d'un conteneur : healthy, starting, unhealthy, exited… ou absent
    $ErrorActionPreference = 'Continue'
    $state = docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $container 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $state) { return 'absent' }
    return ([string]$state).Trim()
}

$previousProxyTarget = $env:API_PROXY_TARGET
Push-Location $root
try {
    Write-Step '1/4  Prérequis'
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Stop-WithError 'Docker est requis : https://docs.docker.com/get-docker/'
    }
    if (-not (Test-Native { docker info })) {
        Stop-WithError 'Docker ne répond pas : démarrez Docker Desktop, puis relancez.'
    }
    if (-not (Test-Native { docker compose version })) {
        Stop-WithError 'Docker Compose v2 est requis (commande « docker compose »).'
    }
    Write-Host "  Docker $(docker version -f '{{.Server.Version}}'), Compose $(docker compose version --short)"

    # L'interface en mode « session » : la connexion passe par Keycloak, via le
    # service (ADR-0012).
    if ($front) {
        if (-not (Get-Command node -ErrorAction SilentlyContinue) -or -not (Get-Command npm -ErrorAction SilentlyContinue)) {
            $front = $false
            $frontSkipped = "Node.js est absent : installez Node 22.12+ (https://nodejs.org) pour l'interface"
        } else {
            $nodeVersion = ([string](node -p 'process.versions.node')).Trim()
            if ([version]$nodeVersion -lt [version]'22.12.0') {
                $front = $false
                $frontSkipped = "Node $nodeVersion trouvé, 22.12+ requis pour l'interface"
            } else {
                Write-Host "  Node $nodeVersion"
            }
        }
    }
    if ($frontSkipped) { Write-Host "  (!) Interface non lancée : $frontSkipped" -ForegroundColor Yellow }

    Write-Step '2/4  Construction et démarrage des conteneurs'
    Write-Host '  Premier lancement : quelques minutes (images, dépendances Maven, signatures antivirus).'
    # Compose 5 peut sélectionner sa progression interactive même si sa sortie
    # traverse le pipeline de Invoke-Native. Sous Windows PowerShell, il tente
    # alors d'utiliser un handle de console qui n'est plus valide. Le rendu
    # « plain » fonctionne dans une console comme dans une sortie redirigée.
    if ((Invoke-Native { docker compose --progress plain --profile app up -d --build }) -ne 0) {
        Stop-WithError 'Le démarrage des conteneurs a échoué (voir ci-dessus). « port is already allocated » : README.txt §8.'
    }

    Write-Step '3/4  Attente des services'
    $services = 'postgres', 'objectstore', 'keycloak', 'antivirus', 'backend'
    $deadline = (Get-Date).AddSeconds($waitSeconds)
    $last = ''
    while ($true) {
        $pending = @()
        foreach ($service in $services) {
            $state = Get-Health "praxedo-$service"
            if ($state -eq 'healthy') { continue }
            if ($state -in 'unhealthy', 'exited', 'dead', 'absent') {
                Stop-WithError "$service est « $state ». Journaux : docker compose logs $service"
            }
            $pending += $service
        }
        if ($pending.Count -eq 0) { break }
        $current = $pending -join ' '
        if ($current -ne $last) {
            Write-Host "  en attente : $current"
            if ($pending -contains 'antivirus') {
                Write-Host "    (l'antivirus télécharge et charge ses signatures : 1 à 2 min au premier démarrage ;"
                Write-Host '     le service accepte déjà les dépôts, ils attendent dans la file)'
            }
            $last = $current
        }
        if ((Get-Date) -ge $deadline) {
            Invoke-Native { docker compose --profile app ps } | Out-Null
            if ((Get-Health 'praxedo-backend') -ne 'healthy') {
                Stop-WithError "Le service n'est pas prêt après $waitSeconds s. Journaux : docker compose logs backend"
            }
            Write-Host "  (!) Toujours en attente après $waitSeconds s : $current - on continue, le service est prêt." -ForegroundColor Yellow
            break
        }
        Start-Sleep -Seconds 5
    }
    Write-Host '  tout est prêt'

    Write-Step '4/4  Adresses'
    Write-Host @"
  Service (API)    http://localhost:$backendPort/api/v1/files
                   santé http://localhost:8091/actuator/health · métriques /actuator/prometheus (port de management, local)
  Keycloak         http://localhost:$keycloakPort        console : admin / admin
                   realm praxedo, comptes sylvain.rivollet, juliette.coudyser, nathan.martin,
                   wessim.jazzar - mot de passe : demo
  Antivirus        http://localhost:$antivirusPort/version
  Stockage S3      http://localhost:$s3Port        console : http://localhost:$s3UiPort
  PostgreSQL       localhost:$postgresPort           bases praxedo et keycloak - praxedo / praxedo

  Vérifier chaque brique :  powershell -ExecutionPolicy Bypass -File scripts\check.ps1
  Tout arrêter :            powershell -ExecutionPolicy Bypass -File scripts\stop.ps1
"@

    $example = @'

  Authentification Keycloak, toujours active.
    Navigateur : « Se connecter » -> page Keycloak (l'un des comptes ci-dessus / demo) ;
                 le service garde les jetons, le navigateur n'a qu'un cookie HttpOnly.
    Système tiers (jeton Bearer, client confidentiel praxedo-integration) :
    $secret = 'praxedo-local-integration-secret-do-not-reuse'
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("praxedo-integration:$secret"))
    $token = (Invoke-RestMethod -Method Post http://localhost:{KEYCLOAK}/realms/praxedo/protocol/openid-connect/token `
      -Headers @{ Authorization = "Basic $basic" } -Body @{ grant_type = 'client_credentials' }).access_token
    Invoke-RestMethod http://localhost:{BACKEND}/api/v1/files -Headers @{ Authorization = "Bearer $token" }
'@
    Write-Host $example.Replace('{KEYCLOAK}', $keycloakPort).Replace('{BACKEND}', $backendPort)

    if (-not $front) { exit 0 }

    Write-Step "Interface React - branchée sur le service"
    $portTaken = $true
    try { Invoke-WebRequest -Uri 'http://127.0.0.1:5173/' -UseBasicParsing -TimeoutSec 2 | Out-Null } catch {
        $portTaken = $null -ne $_.Exception.Response
    }
    if ($portTaken) {
        Stop-WithError ("Le port 5173 est déjà pris : une interface tourne déjà ? Arrêtez-la (Ctrl+C dans son terminal), puis relancez.`n" +
            'scripts\check.ps1 dit si elle est branchée sur le service ou sur des bouchons.')
    }
    Set-Location (Join-Path $root 'frontend')
    $installed = Join-Path 'node_modules' '.package-lock.json'
    # node_modules contient des binaires natifs (Vite, Tailwind) : installé sous
    # Linux ou WSL, il ne sert pas sous Windows, et inversement. La plateforme
    # de l'installation est notée à côté ; si elle change, on réinstalle.
    $platform = (node -p "process.platform + '-' + process.arch" | Out-String).Trim()
    $marker = Join-Path $root 'frontend\node_modules\.platform'
    $installedFor = ''
    if (Test-Path $marker) { $installedFor = (Get-Content $marker | Out-String).Trim() }
    if ($installedFor -and $installedFor -ne $platform) {
        Write-Host "  dépendances installées pour $installedFor, cette machine est $platform"
    }
    if (-not (Test-Path $installed) -or (Get-Item 'package-lock.json').LastWriteTime -gt (Get-Item $installed).LastWriteTime -or $installedFor -ne $platform) {
        Write-Host '  installation des dépendances (npm ci)...'
        if ((Invoke-Native { npm ci --no-audit --no-fund }) -ne 0) { Stop-WithError "L'installation des dépendances a échoué." }
        [IO.File]::WriteAllText($marker, $platform)
    }
    Write-Host "  Ouvrir http://127.0.0.1:5173 - Ctrl+C arrête l'interface (le reste continue de tourner)"
    Write-Host ''
    # Le serveur de développement relaie /api vers le service : même origine,
    # donc ni CORS ni liens de téléchargement à réécrire (frontend/vite.config.ts).
    $env:API_PROXY_TARGET = "http://localhost:$backendPort"
    $ErrorActionPreference = 'Continue'
    npm run dev
    exit $LASTEXITCODE
} finally {
    Pop-Location
    $env:API_PROXY_TARGET = $previousProxyTarget
}
