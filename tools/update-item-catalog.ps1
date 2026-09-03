[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$ItemsZip = "items.zip",

    [switch]$DryRun,

    [switch]$SkipCompile
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

Push-Location $repoRoot
try {
    if (-not $SkipCompile) {
        & mvn -B -DskipTests compile
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }

    $arguments = @(
        '-cp', (Join-Path $repoRoot 'target\classes'),
        'pl.laina.reforge.catalog.CatalogEvolutionUpdater',
        '--source', $ItemsZip
    )
    if ($DryRun) {
        $arguments += '--dry-run'
    }

    & java @arguments
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
