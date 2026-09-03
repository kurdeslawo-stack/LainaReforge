[CmdletBinding()]
param(
    [string]$Decisions = "recycling-decisions.yml",
    [string]$Queue = "generated/recycling-decision-queue.yml",
    [string]$Output = "src/main/resources/recycling-runtime.yml",
    [string]$Report = "generated/approved-decisions-runtime-report.txt",
    [string]$DeployPath,
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
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    if ($DeployPath) {
        $sourcePath = (Resolve-Path -LiteralPath $Output).Path
        $destinationPath = if ([System.IO.Path]::IsPathRooted($DeployPath)) {
            [System.IO.Path]::GetFullPath($DeployPath)
        } else {
            [System.IO.Path]::GetFullPath((Join-Path $repoRoot $DeployPath))
        }
        $destinationDirectory = Split-Path -Parent $destinationPath
        if (-not $destinationDirectory) {
            throw 'DeployPath must point to a file.'
        }
        New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
        $temporaryPath = Join-Path $destinationDirectory `
            ((Split-Path -Leaf $destinationPath) + '.' + [System.Guid]::NewGuid().ToString('N') + '.tmp')
        try {
            Copy-Item -LiteralPath $sourcePath -Destination $temporaryPath
            Move-Item -LiteralPath $temporaryPath -Destination $destinationPath -Force
            Write-Host "Deployed runtime: $destinationPath"
        } finally {
            if (Test-Path -LiteralPath $temporaryPath) {
                Remove-Item -LiteralPath $temporaryPath -Force
            }
        }
    }
    exit 0
} finally {
    Pop-Location
}
