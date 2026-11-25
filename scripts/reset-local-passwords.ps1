$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not (Test-Path 'compose.yaml')) { throw 'Run from the assessment repository.' }
$lines = @()
if (Test-Path '.env') { $lines = @(Get-Content '.env') }
$updates = @()
foreach ($role in @('TEACHER', 'MARKER', 'MODERATOR')) {
    $secure = Read-Host "Choose a NEW password for $($role.ToLower())@school.local (12-72 characters; no single quotes)" -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        if ($plain.Length -lt 12 -or [Text.Encoding]::UTF8.GetByteCount($plain) -gt 72 -or $plain.Contains("'") -or $plain.Contains("`r") -or $plain.Contains("`n")) {
            throw 'Use at least 12 characters, at most 72 UTF-8 bytes, without single quotes or line breaks.'
        }
        $updates += "$($role)_PASSWORD='$plain'"
        $plain = $null
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $secure.Dispose()
    }
}
$lines = @($lines | Where-Object { $_ -notmatch '^\s*(TEACHER|MARKER|MODERATOR)_PASSWORD\s*=' })
[IO.File]::WriteAllLines((Join-Path (Get-Location) '.env'), @($lines + $updates), (New-Object Text.UTF8Encoding($false)))
$updates = $null
foreach ($role in @('TEACHER','MARKER','MODERATOR')) {
    Remove-Item "Env:$($role)_PASSWORD" -ErrorAction SilentlyContinue
}
docker compose up -d --no-deps --force-recreate app
if ($LASTEXITCODE -ne 0) { throw 'Passwords saved, but container recreation failed. Start Docker Desktop and rerun docker compose up -d.' }
Write-Host 'Passwords updated. Open http://localhost:8080/login and use the new password you entered.'
Write-Host 'Teacher login: teacher@school.local'
Write-Host 'Marker login: marker@school.local'
Write-Host 'Moderator login: moderator@school.local'
