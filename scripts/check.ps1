<#
.SYNOPSIS
    Vérifie que chaque brique répond, ET qu'elle fait ce qu'elle promet.

.DESCRIPTION
    PostgreSQL   les deux bases existent, le schéma du service est migré
    Stockage     la livraison ne peut PAS lire la quarantaine - et lit la zone servable
    Antivirus    un contenu sain passe, EICAR est détecté
    Keycloak     un jeton est délivré, avec l'audience attendue par le service
    Service      prêt, refuse qui n'a pas de jeton, accepte le jeton du système tiers
    Interface    servie, et branchée sur le service

    powershell -ExecutionPolicy Bypass -File scripts\check.ps1

    Rien de durable n'est écrit : l'objet de test du stockage est effacé, et
    EICAR n'est jamais écrit sur le disque (l'antivirus du poste le mettrait en
    quarantaine) : la chaîne est assemblée en mémoire. Équivalent bash : scripts/check.sh.
#>
$ErrorActionPreference = 'Stop'

$backend = "http://localhost:$(if ($env:BACKEND_PORT) { $env:BACKEND_PORT } else { '8080' })"
$management = "http://localhost:$(if ($env:BACKEND_MANAGEMENT_PORT) { $env:BACKEND_MANAGEMENT_PORT } else { '8091' })"
$keycloak = "http://localhost:$(if ($env:KEYCLOAK_PORT) { $env:KEYCLOAK_PORT } else { '8081' })"
$antivirus = "http://localhost:$(if ($env:ANTIVIRUS_PORT) { $env:ANTIVIRUS_PORT } else { '9000' })"
$frontend = 'http://127.0.0.1:5173'
$pgUser = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'praxedo' }

$script:failures = 0
function Write-Section([string]$text) { Write-Host ''; Write-Host $text -ForegroundColor Cyan }
function Write-Ok([string]$text) { Write-Host '  [OK] ' -ForegroundColor Green -NoNewline; Write-Host $text }
function Write-Ko([string]$text) { Write-Host '  [KO] ' -ForegroundColor Red -NoNewline; Write-Host $text; $script:failures++ }
function Write-Info([string]$text) { Write-Host "  [--] $text" }

# Commandes natives : voir start.ps1 pour la raison du relâchement de
# $ErrorActionPreference. C'est le code de sortie qui fait foi.
function Get-Health([string]$container) {
    $ErrorActionPreference = 'Continue'
    $state = docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $container 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $state) { return 'absent' }
    return ([string]$state).Trim()
}
function Invoke-Psql([string]$database, [string]$sql) {
    $ErrorActionPreference = 'Continue'
    $result = docker exec praxedo-postgres psql -U $pgUser -d $database -tAc $sql 2>$null
    if ($LASTEXITCODE -ne 0) { return '' }
    return ([string]$result).Trim()
}
# Client S3 officiel, avec l'identité demandée, sur le réseau des conteneurs.
function Test-S3([string]$identity, [string[]]$arguments) {
    $ErrorActionPreference = 'Continue'
    docker run --rm --network praxedo_default `
        -e "AWS_ACCESS_KEY_ID=$identity" -e "AWS_SECRET_ACCESS_KEY=$identity-secret" -e AWS_DEFAULT_REGION=us-east-1 `
        amazon/aws-cli:2.32.9@sha256:734684f3fc98bbac0e7796c34f44c375c01f05febac52a10649a55eef1e96c24 --endpoint-url http://objectstore:8333 s3api @arguments *> $null
    return $LASTEXITCODE -eq 0
}
# Requête HTTP qui ne lève jamais : rend le code (0 si injoignable) et le corps.
function Invoke-Http([string]$uri, [string]$method = 'GET', $body = $null, [hashtable]$headers = @{}) {
    $parameters = @{ Uri = $uri; Method = $method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 10 }
    if ($null -ne $body) { $parameters.Body = $body; $parameters.ContentType = 'application/octet-stream' }
    try {
        $response = Invoke-WebRequest @parameters
        # PowerShell 5.1 rend des octets quand il ne reconnaît pas le type comme
        # du texte (application/vnd.spring-boot.actuator.v3+json, par exemple).
        $content = $response.Content
        if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = [string]$content }
    } catch {
        if ($null -ne $_.Exception.Response) {
            $text = if ($_.ErrorDetails) { $_.ErrorDetails.Message } else { '' }
            return [pscustomobject]@{ Status = [int]$_.Exception.Response.StatusCode; Body = $text }
        }
        return [pscustomobject]@{ Status = 0; Body = $_.Exception.Message }
    }
}

Write-Section 'Conteneurs'
foreach ($service in 'postgres', 'objectstore', 'keycloak', 'antivirus', 'backend') {
    $state = Get-Health "praxedo-$service"
    if ($state -eq 'healthy') { Write-Ok $service } else { Write-Ko "$service : $state" }
}
$init = & { $ErrorActionPreference = 'Continue'; docker inspect -f '{{.State.ExitCode}}' praxedo-objectstore-init 2>$null }
if ("$init".Trim() -eq '0') { Write-Ok 'zones de stockage créées' } else { Write-Ko "création des zones de stockage : $init" }

Write-Section 'PostgreSQL'
$databases = Invoke-Psql 'postgres' "select string_agg(datname, ' ' order by datname) from pg_database where datname in ('praxedo', 'keycloak')"
if ($databases -eq 'keycloak praxedo') { Write-Ok 'bases praxedo (le service) et keycloak' } else { Write-Ko "bases trouvées : « $databases »" }
$migrations = Invoke-Psql 'praxedo' "select count(*) || ' migrations, dernière V' || max(version::int) from flyway_schema_history where success"
if ($migrations) { Write-Ok "schéma migré par Flyway : $migrations" } else { Write-Ko 'schéma non migré (le service a-t-il démarré ?)' }
$roles = Invoke-Psql 'postgres' "select count(*) from pg_roles where rolname in ('praxedo_owner', 'praxedo_app', 'keycloak') and not rolsuper"
if ($roles -eq '3') { Write-Ok 'un rôle par usage, aucun superutilisateur : praxedo_owner (migrations), praxedo_app (service), keycloak' }
else { Write-Ko "rôles praxedo_owner, praxedo_app, keycloak : « $roles » sur 3 (volume antérieur ? docker compose down -v)" }
$sessions = Invoke-Psql 'postgres' "select string_agg(distinct datname || ':' || usename, ' ') from pg_stat_activity where datname in ('praxedo', 'keycloak') and pid <> pg_backend_pid()"
if (" $sessions " -match " (praxedo|keycloak):$pgUser ") { Write-Ko "le superutilisateur sert une application : « $sessions »" }
elseif ($sessions -match 'praxedo:praxedo_app') { Write-Ok "le service se connecte en praxedo_app ($sessions)" }
else { Write-Ko "aucune session praxedo_app : « $sessions »" }

Write-Section "Stockage objet - l'isolation qui porte la garantie"
$key = "check-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())-$PID.txt"
if (Test-S3 'praxedo-ingest' @('put-object', '--bucket', 'quarantine', '--key', $key, '--body', '/etc/hostname')) {
    Write-Ok 'le dépôt écrit dans la quarantaine'
} else {
    Write-Ko "le dépôt n'écrit pas dans la quarantaine"
}
if (Test-S3 'praxedo-delivery' @('get-object', '--bucket', 'quarantine', '--key', $key, '/tmp/out')) {
    Write-Ko "la livraison LIT la quarantaine : l'isolation n'est pas effective"
} else {
    Write-Ok 'la livraison ne peut pas lire la quarantaine (refusé par le stockage)'
}
if ((Test-S3 'praxedo-worker' @('put-object', '--bucket', 'servable', '--key', $key, '--body', '/etc/hostname')) -and
    (Test-S3 'praxedo-delivery' @('get-object', '--bucket', 'servable', '--key', $key, '/tmp/out'))) {
    Write-Ok 'contre-épreuve : la livraison lit la zone servable'
} else {
    Write-Ko 'la livraison ne lit pas la zone servable : elle ne pourrait rien servir'
}
Test-S3 'praxedo-worker' @('delete-object', '--bucket', 'quarantine', '--key', $key) | Out-Null
Test-S3 'praxedo-worker' @('delete-object', '--bucket', 'servable', '--key', $key) | Out-Null

Write-Section 'Antivirus'
$version = Invoke-Http "$antivirus/version"
if ($version.Status -eq 200) {
    try {
        $engine = $version.Body | ConvertFrom-Json
        Write-Info "ClamAV $($engine.Clamav), signatures $($engine.Signature)"
    } catch { Write-Info $version.Body }
}
$clean = Invoke-Http "$antivirus/scanHandlerBody" 'POST' 'bonjour'
if ($clean.Status -eq 200) { Write-Ok 'contenu sain -> 200' } else { Write-Ko "contenu sain -> « $($clean.Status) » (200 attendu)" }
$eicar = 'X5O!P%@AP[4\PZX54(P^)7CC)7}' + '$EICAR-STANDARD-' + 'ANTIVIRUS-TEST-FILE!' + '$H+H*'
$threat = Invoke-Http "$antivirus/scanHandlerBody" 'POST' $eicar
if ($threat.Status -eq 406 -and $threat.Body -match '(Eicar[A-Za-z.-]*)') {
    Write-Ok "EICAR détecté -> 406 ($($Matches[1]))"
} else {
    Write-Ko "EICAR non détecté : « $($threat.Status) $($threat.Body) » (les signatures sont-elles chargées ?)"
}

Write-Section 'Keycloak'
$token = $null
try {
    $secret = if ($env:PRAXEDO_INTEGRATION_SECRET) { $env:PRAXEDO_INTEGRATION_SECRET } else { 'praxedo-local-integration-secret-do-not-reuse' }
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("praxedo-integration:$secret"))
    $answer = Invoke-RestMethod -Method Post -TimeoutSec 10 -Uri "$keycloak/realms/praxedo/protocol/openid-connect/token" `
        -Headers @{ Authorization = "Basic $basic" } -Body @{ grant_type = 'client_credentials' }
    $token = $answer.access_token
} catch { }
if ($token) {
    Write-Ok 'jeton délivré au système tiers praxedo-integration (client credentials)'
    $payload = $token.Split('.')[1].Replace('-', '+').Replace('_', '/')
    switch ($payload.Length % 4) { 2 { $payload += '==' } 3 { $payload += '=' } }
    $claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json
    if (@($claims.aud) -contains 'praxedo-files-api') {
        Write-Ok 'audience praxedo-files-api présente (le service la vérifie)'
    } else {
        Write-Ko 'audience praxedo-files-api absente du jeton : le service le refuserait'
    }
} else {
    Write-Ko 'aucun jeton délivré'
}

Write-Section 'Exposition'
$exposed = & { $ErrorActionPreference = 'Continue'; docker ps --filter name=praxedo- --format '{{.Names}} {{.Ports}}' 2>$null } |
    Where-Object { $_ -match '0\.0\.0\.0:|\[::\]:|:::' }
if (-not $exposed) { Write-Ok 'aucun port publié hors de 127.0.0.1' } else { Write-Ko "ports ouverts sur le réseau : $($exposed -join ' | ')" }

Write-Section 'Service'
$readiness = Invoke-Http "$management/actuator/health/readiness"
if ($readiness.Body -match '"UP"') { Write-Ok 'prêt (sonde sur le port de management)' } else { Write-Ko "pas prêt : « $($readiness.Status) $($readiness.Body) »" }
$metrics = Invoke-Http "$backend/actuator/prometheus"
if ($metrics.Status -in 401, 404) { Write-Ok "le port de l'API ne sert pas les métriques -> $($metrics.Status)" } else { Write-Ko "métriques sur le port de l'API -> « $($metrics.Status) » (401 ou 404 attendu)" }
$files = Invoke-Http "$backend/api/v1/files"
switch ($files.Status) {
    401 {
        Write-Ok "sans jeton -> 401 : l'authentification Keycloak est exigée"
        if ($token -and (Invoke-Http "$backend/api/v1/files" 'GET' $null @{ Authorization = "Bearer $token" }).Status -eq 200) {
            Write-Ok 'jeton du système tiers accepté'
        } else {
            Write-Ko 'jeton du système tiers refusé'
        }
        # Sans suivre la redirection : c'est elle qu'on vérifie (PowerShell 5.1 et 7).
        $loginStatus = 0
        try {
            $login = Invoke-WebRequest -Uri "$backend/api/v1/auth/login" -UseBasicParsing -TimeoutSec 10 `
                -MaximumRedirection 0 -ErrorAction SilentlyContinue
            if ($login) { $loginStatus = [int]$login.StatusCode }
        } catch {
            if ($null -ne $_.Exception.Response) { $loginStatus = [int]$_.Exception.Response.StatusCode }
        }
        if ($loginStatus -eq 302) {
            Write-Ok 'connexion du navigateur : /api/v1/auth/login redirige vers Keycloak'
        } else {
            Write-Ko 'connexion du navigateur : /api/v1/auth/login ne redirige pas'
        }
    }
    default { Write-Ko "liste des fichiers sans jeton -> « $($files.Status) », 401 attendu" }
}

Write-Section 'Interface'
if ((Invoke-Http "$frontend/").Status -eq 200) {
    Write-Ok "servie sur $frontend"
    # Le serveur de développement injecte sa configuration dans chaque module :
    # c'est là qu'on voit si le navigateur parle au service ou à des bouchons.
    $relayed = (Invoke-Http "$frontend/api/v1/files").Status
    if ((Invoke-Http "$frontend/src/config/env.ts").Body -match '"VITE_API_MOCKING": *"true"') {
        Write-Ko "elle tourne sur des bouchons (npm run dev:mock) : elle n'utilise pas le service - arrêtez-la, relancez start"
    } elseif ($relayed -in 200, 401) {
        Write-Ok "branchée sur le service (le relais /api répond $relayed)"
    } else {
        Write-Ko "le relais /api ne joint pas le service : « $relayed »"
    }
} else {
    Write-Info 'non lancée - scripts\start.ps1 la lance'
}

Write-Host ''
if ($script:failures -eq 0) {
    Write-Host 'Tout est en ordre.' -ForegroundColor Green
} else {
    Write-Host "$($script:failures) vérification(s) en échec. Journaux : docker compose logs <service>" -ForegroundColor Red
    exit 1
}
