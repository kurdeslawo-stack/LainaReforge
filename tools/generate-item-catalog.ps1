[CmdletBinding()]
param(
    [string]$SourceZip,
    [string]$CatalogPath,
    [string]$ReportPath,
    [switch]$SkipCompile
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($SourceZip)) {
    $SourceZip = Join-Path $repoRoot 'items.zip'
}
if ([string]::IsNullOrWhiteSpace($CatalogPath)) {
    $CatalogPath = Join-Path $repoRoot 'src\main\resources\items.yml'
}
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $repoRoot 'generated\item-catalog-report.txt'
}

Push-Location $repoRoot
try {
    if (-not $SkipCompile) {
        & mvn -B -DskipTests compile
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }

    & java -cp (Join-Path $repoRoot 'target\classes') `
        'pl.laina.reforge.catalog.ItemCatalogGenerator' `
        $SourceZip `
        $CatalogPath `
        $ReportPath
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
