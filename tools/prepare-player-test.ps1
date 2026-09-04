[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [string]$ServerRoot = 'C:\CodexProjects\LainaServer\minecraftTest',
    [string]$Maven = 'C:\CodexProjects\apache-maven-3.9.16\bin\mvn.cmd'
)

$ErrorActionPreference = 'Stop'
$root = if ($RepositoryRoot) { [System.IO.Path]::GetFullPath($RepositoryRoot) } else { Split-Path -Parent $PSScriptRoot }
$server = [System.IO.Path]::GetFullPath($ServerRoot)
$mavenPath = [System.IO.Path]::GetFullPath($Maven)
$workspace = Join-Path $root 'target\player-acceptance'
$backup = Join-Path $root 'target\player-acceptance-backup'
$jarSource = Join-Path $root 'target\LainaReforge-0.1.0-rc1.jar'
$jarTarget = Join-Path $server 'plugins\LainaReforge.jar'
$runtimeTarget = Join-Path $server 'plugins\LainaReforge\recycling-runtime.yml'
$queue = Join-Path $root 'generated\recycling-decision-queue.yml'
$checklist = Join-Path $root 'generated\player-acceptance-checklist.txt'
$decisions = Join-Path $workspace 'recycling-decisions.yml'
$runtime = Join-Path $workspace 'recycling-runtime.yml'
$compilerReport = Join-Path $workspace 'compiler-report.txt'

function Assert-ServerStopped {
    $properties = Join-Path $server 'server.properties'
    $port = 25565
    if (Test-Path -LiteralPath $properties -PathType Leaf) {
        $match = Select-String -LiteralPath $properties -Pattern '^server-port=(\d+)$' | Select-Object -First 1
        if ($match) { $port = [int]$match.Matches[0].Groups[1].Value }
    }
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($listener) { throw "Serwer nadal dziala na porcie $port. Zatrzymaj go poprawnie i uruchom skrypt ponownie." }
}

try {
    Assert-ServerStopped
    if ((Test-Path -LiteralPath $backup -PathType Container) -and
            -not (Test-Path -LiteralPath (Join-Path $backup 'RESTORED.txt') -PathType Leaf)) {
        throw "Istnieje aktywny backup testu. Najpierw uruchom RESTORE-AFTER-PLAYER-TEST.cmd.`n$backup"
    }
    foreach ($required in @($mavenPath, $jarTarget, $runtimeTarget, $queue, $checklist)) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Brak wymaganego pliku: $required" }
    }

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'tools\release-preflight.ps1') -RepositoryRoot $root
    if ($LASTEXITCODE -ne 0) { throw 'Release preflight zakonczyl sie bledem.' }

    & $mavenPath "-Dmaven.repo.local=C:\CodexProjects\.m2\repository" -B clean package
    if ($LASTEXITCODE -ne 0) { throw 'Build RC zakonczyl sie bledem.' }
    if (-not (Test-Path -LiteralPath $jarSource -PathType Leaf)) { throw "Brak zbudowanego RC JAR: $jarSource" }

    New-Item -ItemType Directory -Path $workspace -Force | Out-Null
    $fixture = @'
items:
  "Ametystowa_Marchew":
    status: APPROVED
    recyclable: true
    shards: 3
    reviewed_by: "player-acceptance"
    reviewed_at: "2026-09-04T12:00:00Z"
    note: "isolated player fixture"
  "Ametystowa_Rybka":
    status: REJECTED
    recyclable: false
    shards: 0
    reviewed_by: "player-acceptance"
    reviewed_at: "2026-09-04T12:00:00Z"
    note: "isolated player fixture"
  "Ametystowy_Burak":
    status: APPROVED
    recyclable: true
    shards: 7
    reviewed_by: "player-acceptance"
    reviewed_at: "2026-09-04T12:00:00Z"
    note: "isolated player fixture"
  "unmapped::carved_pumpkin:2350507":
    status: APPROVED
    recyclable: true
    shards: 3
    reviewed_by: "player-acceptance"
    reviewed_at: "2026-09-04T12:00:00Z"
    note: "exact material+CMD fixture"
'@
    [System.IO.File]::WriteAllText($decisions, $fixture + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))

    Push-Location $root
    try {
        & java -cp (Join-Path $root 'target\classes') 'pl.laina.reforge.catalog.RecyclingRuntimeCompiler' `
            --queue $queue --decisions $decisions --output $runtime --report $compilerReport
    } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw 'Kompilacja testowego runtime zostala odrzucona.' }

    $runtimeText = [System.IO.File]::ReadAllText($runtime, [System.Text.Encoding]::UTF8)
    foreach ($expected in @('golden_carrot:2350222', 'tropical_fish:2350150',
            'baked_potato:2350221', 'carved_pumpkin:2350507')) {
        if (-not $runtimeText.Contains($expected)) { throw "Testowy runtime nie zawiera identity: $expected" }
    }
    if ($runtimeText.Contains('echo_shard:2350507') -or $runtimeText.Contains('baked_potato:2350223')) {
        throw 'Testowy runtime zawiera identity, ktore mialo pozostac NOT_CONFIGURED.'
    }

    New-Item -ItemType Directory -Path $backup -Force | Out-Null
    Copy-Item -LiteralPath $jarTarget -Destination (Join-Path $backup 'LainaReforge.jar')
    Copy-Item -LiteralPath $runtimeTarget -Destination (Join-Path $backup 'recycling-runtime.yml')
    $manifestLines = @(
        "server_root=$server"
        "created_utc=$([DateTime]::UtcNow.ToString('o'))"
        "jar_sha256=$((Get-FileHash -LiteralPath $jarTarget -Algorithm SHA256).Hash)"
        "runtime_sha256=$((Get-FileHash -LiteralPath $runtimeTarget -Algorithm SHA256).Hash)"
    )
    $manifest = [string]::Join([Environment]::NewLine, $manifestLines)
    [System.IO.File]::WriteAllText((Join-Path $backup 'manifest.txt'), $manifest + [Environment]::NewLine,
            [System.Text.UTF8Encoding]::new($false))

    try {
        Copy-Item -LiteralPath $jarSource -Destination $jarTarget -Force
        Copy-Item -LiteralPath $runtime -Destination $runtimeTarget -Force
    } catch {
        Copy-Item -LiteralPath (Join-Path $backup 'LainaReforge.jar') -Destination $jarTarget -Force
        Copy-Item -LiteralPath (Join-Path $backup 'recycling-runtime.yml') -Destination $runtimeTarget -Force
        throw
    }

    Write-Host '================================'
    Write-Host 'LainaReforge Player Test Ready'
    Write-Host '================================'
    Write-Host ''
    Write-Host 'RC JAR: READY'
    Write-Host 'Test runtime: READY'
    Write-Host 'Server backup: READY'
    Write-Host 'Checklist: READY'
    Write-Host ''
    Write-Host "Checklist: $checklist"
    Write-Host "Restore: $(Join-Path $root 'RESTORE-AFTER-PLAYER-TEST.cmd')"
    Write-Host ''
    Write-Host 'NOW START THE TEST SERVER AND JOIN AS A PLAYER.' -ForegroundColor Green
    exit 0
} catch {
    Write-Host ''
    Write-Host 'PLAYER TEST PREPARATION: FAIL' -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
