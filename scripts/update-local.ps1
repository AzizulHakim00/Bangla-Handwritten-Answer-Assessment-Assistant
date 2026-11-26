[CmdletBinding()]
param([switch]$FollowLogs)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

function Invoke-Compose {
    & docker compose @args
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed (exit $LASTEXITCODE)." }
}

& docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop and wait for the engine before updating.' }

$envPath = Join-Path (Get-Location) '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    Copy-Item -LiteralPath '.env.example' -Destination $envPath
    throw 'Set the three passwords in .env, then rerun this script.'
}

$lines = [IO.File]::ReadAllLines($envPath)
$accountConfig = $lines | Where-Object { $_ -match '^\s*ASSESSMENT_ACCOUNTS_JSON\s*=\s*\S+' }
if (-not $accountConfig -and -not $env:ASSESSMENT_ACCOUNTS_JSON) {
    foreach ($role in 'TEACHER','MARKER','MODERATOR') {
        $name = "${role}_PASSWORD"
        if (-not [Environment]::GetEnvironmentVariable($name) -and
            -not ($lines | Where-Object { $_ -match "^\s*$name\s*=\s*\S+" })) {
            throw "Set $name in .env before updating."
        }
    }
}

if (-not $env:ASSESSMENT_WORKER_TOKEN -and -not ($lines | Where-Object { $_ -match '^\s*ASSESSMENT_WORKER_TOKEN\s*=\s*\S+' })) {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $token = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
    $lines = @($lines | Where-Object { $_ -notmatch '^\s*ASSESSMENT_WORKER_TOKEN\s*=' })
    [IO.File]::WriteAllLines($envPath, [string[]]($lines + "ASSESSMENT_WORKER_TOKEN=$token"), (New-Object Text.UTF8Encoding($false)))
}

Invoke-Compose build
Invoke-Compose stop app
Invoke-Compose run --rm --no-deps --user root --entrypoint chown app -R 10001:10001 /data/scripts
Invoke-Compose up -d --wait
Invoke-Compose ps

$port = $env:ASSESSMENT_HTTP_PORT
if (-not $port) {
    $portLine = $lines | Where-Object { $_ -match '^\s*ASSESSMENT_HTTP_PORT\s*=' } | Select-Object -Last 1
    if ($portLine -match '=\s*["'']?(\d+)') { $port = $Matches[1] }
    else { $port = '8085' }
}
$url = "http://localhost:$port/login"
$loaded = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    try {
        $page = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3
        if ($page.Content -match '\?v=2026\.11\.26-r3\.2"') { $loaded = $true; break }
    } catch {
        Start-Sleep -Seconds 2
        continue
    }
    Start-Sleep -Seconds 2
}
if (-not $loaded) { throw "The new build did not respond at $url. Check docker compose logs --tail 80 app worker mongo." }
Write-Host "Open $url and check for Build 2026.11.26-r3.2."
if ($FollowLogs) { Invoke-Compose logs --tail 80 -f app worker }
