[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [switch]$SkipCompile,
    [switch]$Developer
)

$ErrorActionPreference = 'Stop'
$root = if ($RepositoryRoot) {
    [System.IO.Path]::GetFullPath($RepositoryRoot)
} else {
    Split-Path -Parent $PSScriptRoot
}

function Resolve-Maven {
    $command = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
    if (-not $command) { $command = Get-Command 'mvn' -ErrorAction SilentlyContinue }
    if ($command) { return $command.Source }
    if ($env:LAINAREFORGE_MAVEN -and (Test-Path -LiteralPath $env:LAINAREFORGE_MAVEN -PathType Leaf)) {
        return [System.IO.Path]::GetFullPath($env:LAINAREFORGE_MAVEN)
    }
    $bundled = 'C:\CodexProjects\apache-maven-3.9.16\bin\mvn.cmd'
    if (Test-Path -LiteralPath $bundled -PathType Leaf) { return $bundled }
    throw 'Nie znaleziono Mavena. Dodaj mvn do PATH albo ustaw LAINAREFORGE_MAVEN.'
}

try {
    if (-not (Get-Command 'java' -ErrorAction SilentlyContinue)) {
        throw 'Nie znaleziono Java w PATH.'
    }
    if (-not $SkipCompile) {
        $maven = Resolve-Maven
        $arguments = @('-B', '-DskipTests', 'compile')
        $localRepository = $env:LAINAREFORGE_MAVEN_REPO
        if (-not $localRepository) {
            $candidate = Join-Path (Split-Path -Parent $root) '.m2\repository'
            if (Test-Path -LiteralPath $candidate -PathType Container) { $localRepository = $candidate }
        }
        if ($localRepository) { $arguments = @("-Dmaven.repo.local=$localRepository") + $arguments }
        Push-Location $root
        try { & $maven @arguments } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { throw 'Kompilacja narzedzia preflight nie powiodla sie.' }
    }
    Push-Location $root
    try {
        & java -cp (Join-Path $root 'target\classes') 'pl.laina.reforge.catalog.ReleasePreflight' $root
    } finally {
        Pop-Location
    }
    if ($LASTEXITCODE -ne 0) { exit 1 }
    exit 0
} catch {
    Write-Host '================================'
    Write-Host 'LainaReforge Release Preflight'
    Write-Host '================================'
    Write-Host ''
    Write-Host $_.Exception.Message -ForegroundColor Red
    if ($Developer) { Write-Host $_.ScriptStackTrace -ForegroundColor DarkGray }
    Write-Host ''
    Write-Host 'RELEASE PREFLIGHT: FAIL' -ForegroundColor Red
    exit 1
}
