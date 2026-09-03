[CmdletBinding()]
param(
    [string]$Decisions = "recycling-decisions.yml",
    [string]$Queue = "generated/recycling-decision-queue.yml",
    [string]$Output = "src/main/resources/recycling-runtime.yml",
    [string]$Report = "generated/approved-decisions-runtime-report.txt",
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

    & java -cp (Join-Path $repoRoot 'target\classes') `
        'pl.laina.reforge.catalog.RecyclingRuntimeCompiler' `
        --queue $Queue --decisions $Decisions --output $Output --report $Report
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
