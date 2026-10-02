<#
.SYNOPSIS
    Arrête l'environnement démarré par start.ps1.

.DESCRIPTION
    powershell -ExecutionPolicy Bypass -File scripts\stop.ps1          arrête les conteneurs ; les données sont conservées
    powershell -ExecutionPolicy Bypass -File scripts\stop.ps1 -Purge   arrête et EFFACE les données : base, fichiers
                                                                       déposés, signatures antivirus (re-téléchargées
                                                                       au prochain démarrage)

    L'interface, elle, s'arrête par Ctrl+C dans la fenêtre de start.ps1.
    Équivalent bash : scripts/stop.sh.
#>
param([switch]$Purge)

# Compose écrit sa progression sur stderr : voir start.ps1. Le code de sortie fait foi.
$ErrorActionPreference = 'Continue'

Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    if ($Purge) {
        docker compose --profile app down --volumes
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Write-Host 'Arrêté, données effacées. Le prochain démarrage repart de zéro.'
    } else {
        docker compose --profile app down
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Write-Host "Arrêté. Les données sont conservées ; scripts\start.ps1 reprend là où l'on s'était arrêté."
    }
} finally {
    Pop-Location
}
