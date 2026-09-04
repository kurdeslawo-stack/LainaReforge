[CmdletBinding()]
param(
    [string]$RepositoryRoot,
    [string]$ServerRoot = 'C:\CodexProjects\LainaServer\minecraftTest',
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
$root = if ($RepositoryRoot) { [System.IO.Path]::GetFullPath($RepositoryRoot) } else { Split-Path -Parent $PSScriptRoot }
$server = [System.IO.Path]::GetFullPath($ServerRoot)
$backup = Join-Path $root 'target\player-acceptance-backup'
$jarBackup = Join-Path $backup 'LainaReforge.jar'
$runtimeBackup = Join-Path $backup 'recycling-runtime.yml'
$jarTarget = Join-Path $server 'plugins\LainaReforge.jar'
$runtimeTarget = Join-Path $server 'plugins\LainaReforge\recycling-runtime.yml'

function Assert-ServerStopped {
    $port = 25565
    $properties = Join-Path $server 'server.properties'
    if (Test-Path -LiteralPath $properties -PathType Leaf) {
        $match = Select-String -LiteralPath $properties -Pattern '^server-port=(\d+)$' | Select-Object -First 1
        if ($match) { $port = [int]$match.Matches[0].Groups[1].Value }
    }
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        throw "Serwer nadal dziala na porcie $port. Zatrzymaj go poprawnie przed restore."
    }
}

try {
    Assert-ServerStopped
    foreach ($required in @($jarBackup, $runtimeBackup, (Join-Path $backup 'manifest.txt'))) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Backup jest niekompletny: $required" }
    }
    $manifestValues = @{}
    foreach ($line in Get-Content -LiteralPath (Join-Path $backup 'manifest.txt')) {
        $separator = $line.IndexOf('=')
        if ($separator -gt 0) { $manifestValues[$line.Substring(0, $separator)] = $line.Substring($separator + 1) }
    }
    if ($manifestValues['server_root'] -ne $server) {
        throw 'Backup pochodzi z innej sciezki serwera.'
    }
    if ($manifestValues['jar_sha256'] -ne (Get-FileHash -LiteralPath $jarBackup -Algorithm SHA256).Hash -or
            $manifestValues['runtime_sha256'] -ne (Get-FileHash -LiteralPath $runtimeBackup -Algorithm SHA256).Hash) {
        throw 'Backup nie przeszedl kontroli integralnosci SHA-256.'
    }
    if ($VerifyOnly) {
        Write-Host "RESTORE READY: $backup" -ForegroundColor Green
        exit 0
    }
    Copy-Item -LiteralPath $jarBackup -Destination $jarTarget -Force
    Copy-Item -LiteralPath $runtimeBackup -Destination $runtimeTarget -Force
    [System.IO.File]::WriteAllText((Join-Path $backup 'RESTORED.txt'),
            'Restored at ' + [DateTime]::UtcNow.ToString('o') + [Environment]::NewLine,
            [System.Text.UTF8Encoding]::new($false))
    Write-Host 'Previous LainaReforge JAR and runtime restored.' -ForegroundColor Green
    Write-Host 'Player acceptance result files were not removed.'
    exit 0
} catch {
    Write-Host 'RESTORE: FAIL' -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
