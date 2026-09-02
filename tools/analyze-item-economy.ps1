[CmdletBinding()]
param(
    [switch]$Refresh,
    [switch]$Offline,
    [switch]$SkipCompile,
    [ValidateRange(0, 60000)]
    [int]$DelayMilliseconds = 250
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

if ($Refresh -and $Offline) {
    throw '-Refresh and -Offline cannot be used together.'
}

Push-Location $repoRoot
try {
    if (-not $SkipCompile) {
        & mvn -B -DskipTests compile
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }

    $analyzerArguments = @(
        '--delay-ms', $DelayMilliseconds.ToString([Globalization.CultureInfo]::InvariantCulture)
    )
    if ($Refresh) {
        $analyzerArguments += '--refresh'
    }
    if ($Offline) {
        $analyzerArguments += '--offline'
    }

    & java -cp (Join-Path $repoRoot 'target\classes') `
        'pl.laina.reforge.catalog.ItemEconomyAnalyzer' `
        @analyzerArguments
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
