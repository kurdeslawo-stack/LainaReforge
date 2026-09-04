[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [switch]$PreflightOnly,
    [switch]$Developer
)

$ErrorActionPreference = 'Stop'
$root = if ($RepositoryRoot) {
    [System.IO.Path]::GetFullPath($RepositoryRoot)
} else {
    Split-Path -Parent $PSScriptRoot
}

function Write-Check([string]$Name, [string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Brak wymaganego pliku: $Name`n$Path"
    }
    Write-Host "[OK] $Name"
}

try {
    $panel = Join-Path $root 'generated\recycling-review-panel\index.html'
    $queue = Join-Path $root 'generated\recycling-decision-queue.yml'
    $catalog = Join-Path $root 'src\main\resources\items.yml'
    $snapshot = Join-Path $root 'generated\item-catalog-snapshot.yml'

    Write-Check 'Panel' $panel
    Write-Check 'Queue' $queue
    Write-Check 'Catalog' $catalog
    Write-Check 'Snapshot' $snapshot

    $panelText = [System.IO.File]::ReadAllText($panel, [System.Text.Encoding]::UTF8)
    $match = [regex]::Match($panelText, '<meta name="laina-queue-sha256" content="([0-9a-f]{64})">')
    if (-not $match.Success) {
        throw 'Panel nie zawiera informacji pozwalającej potwierdzić zgodność z kolejką. Wygeneruj panel ponownie.'
    }
    $queueHash = (Get-FileHash -LiteralPath $queue -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($match.Groups[1].Value -ne $queueHash) {
        throw 'STALE_PANEL: Panel jest starszy niż aktualna kolejka lub pochodzi z innej wersji. Wygeneruj panel ponownie.'
    }
    Write-Host '[OK] Zgodność panelu z kolejką'
    Write-Host 'PREFLIGHT_CONSISTENCY_PASS'

    if ($PreflightOnly) {
        Write-Host 'Preflight zakończony pomyślnie. Panel nie został otwarty.'
        exit 0
    }
    Start-Process -FilePath $panel
    Write-Host 'Panel review został otwarty w domyślnej przeglądarce.'
    exit 0
} catch {
    Write-Host ''
    Write-Host 'Nie można bezpiecznie uruchomić panelu review.' -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    if ($Developer) {
        Write-Host $_.ScriptStackTrace -ForegroundColor DarkGray
    }
    exit 1
}
