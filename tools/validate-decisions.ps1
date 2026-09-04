[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [string]$DecisionsPath,
    [string]$QueuePath,
    [string]$ValidationPath,
    [switch]$SkipCompile,
    [switch]$Developer
)

$ErrorActionPreference = 'Stop'
$root = if ($RepositoryRoot) {
    [System.IO.Path]::GetFullPath($RepositoryRoot)
} else {
    Split-Path -Parent $PSScriptRoot
}
$validationDirectory = if ($ValidationPath) { [System.IO.Path]::GetFullPath($ValidationPath) } else { Join-Path $root 'target\reviewer-validation' }
$decisions = if ($DecisionsPath) { [System.IO.Path]::GetFullPath($DecisionsPath) } else { Join-Path $root 'recycling-decisions.yml' }
$queue = if ($QueuePath) { [System.IO.Path]::GetFullPath($QueuePath) } else { Join-Path $root 'generated\recycling-decision-queue.yml' }
$runtime = Join-Path $validationDirectory 'recycling-runtime.yml'
$report = Join-Path $validationDirectory 'validation-report.txt'

function Show-Header {
    Write-Host '================================'
    Write-Host 'LainaReforge Decision Validation'
    Write-Host '================================'
}

function Resolve-Maven {
    $command = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
    if (-not $command) { $command = Get-Command 'mvn' -ErrorAction SilentlyContinue }
    if ($command) { return $command.Source }
    if ($env:LAINAREFORGE_MAVEN -and (Test-Path -LiteralPath $env:LAINAREFORGE_MAVEN -PathType Leaf)) {
        return [System.IO.Path]::GetFullPath($env:LAINAREFORGE_MAVEN)
    }
    throw @'
Nie znaleziono Mavena.
Dodaj mvn do PATH albo ustaw LAINAREFORGE_MAVEN na pełną ścieżkę do mvn.cmd.
Przykład: $env:LAINAREFORGE_MAVEN = 'C:\ścieżka\do\mvn.cmd'
'@
}

try {
    if (-not (Test-Path -LiteralPath $decisions -PathType Leaf)) {
        throw "Nie znaleziono recycling-decisions.yml.`nWyeksportuj decyzje z panelu i umieść plik w katalogu głównym repo."
    }
    if (-not (Test-Path -LiteralPath $queue -PathType Leaf)) {
        throw 'Nie znaleziono kolejki decyzji. Repozytorium jest niekompletne.'
    }
    if (-not $SkipCompile) {
        $maven = Resolve-Maven
        $mavenArguments = @('-B', '-DskipTests', 'compile')
        $localRepository = $env:LAINAREFORGE_MAVEN_REPO
        if (-not $localRepository) {
            $candidate = Join-Path (Split-Path -Parent $root) '.m2\repository'
            if (Test-Path -LiteralPath $candidate -PathType Container) { $localRepository = $candidate }
        }
        if ($localRepository) {
            $mavenArguments = @("-Dmaven.repo.local=$localRepository") + $mavenArguments
        }
        Push-Location $root
        try { & $maven @mavenArguments } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { throw 'Kompilacja narzędzia walidującego nie powiodła się.' }
    }

    New-Item -ItemType Directory -Path $validationDirectory -Force | Out-Null
    Push-Location $root
    try {
        & java -cp (Join-Path $root 'target\classes') `
            'pl.laina.reforge.catalog.RecyclingRuntimeCompiler' `
            --queue $queue --decisions $decisions --output $runtime --report $report
    } finally {
        Pop-Location
    }
    if ($LASTEXITCODE -ne 0) { throw 'Plik decyzji został odrzucony przez compiler. Produkcyjny runtime nie został zmieniony.' }

    $reportText = [System.IO.File]::ReadAllText($report, [System.Text.Encoding]::UTF8)
    $approved = [int]([regex]::Match($reportText, '(?m)^APPROVED: (\d+)$').Groups[1].Value)
    $rejected = [int]([regex]::Match($reportText, '(?m)^REJECTED: (\d+)$').Groups[1].Value)
    $pending = [int]([regex]::Match($reportText, '(?m)^NOT_CONFIGURED logical items: (\d+)$').Groups[1].Value)

    Show-Header
    Write-Host 'Decisions: PASS'
    Write-Host 'Catalog: PASS'
    Write-Host 'Compiler: PASS'
    Write-Host 'Runtime validation: PASS'
    Write-Host ''
    Write-Host "APPROVED: $approved"
    Write-Host "REJECTED: $rejected"
    Write-Host "PENDING: $pending"
    Write-Host ''
    if ($pending -eq 0) {
        Write-Host 'READY FOR DEPLOYMENT' -ForegroundColor Green
    } else {
        Write-Host 'NOT READY FOR DEPLOYMENT' -ForegroundColor Yellow
        Write-Host 'Walidacja techniczna przeszła, ale katalog nadal zawiera pozycje bez decyzji.'
    }
    exit 0
} catch {
    Show-Header
    Write-Host $_.Exception.Message -ForegroundColor Red
    if ($Developer) { Write-Host $_.ScriptStackTrace -ForegroundColor DarkGray }
    Write-Host ''
    Write-Host 'NOT READY FOR DEPLOYMENT' -ForegroundColor Red
    exit 1
}
