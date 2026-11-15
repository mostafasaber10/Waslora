param(
    [string]$Token
)

$ErrorActionPreference = 'Stop'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker Desktop is required. Install it, then run this script again.'
}

if ([string]::IsNullOrWhiteSpace($Token)) {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $Token = [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

$env:WASLORA_RESOLVER_TOKEN = $Token
Push-Location $PSScriptRoot
try {
    docker compose up --build -d
    if ($LASTEXITCODE -ne 0) { throw 'Docker could not start the Waslora Resolver.' }
} finally {
    Pop-Location
}

$addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
    Select-Object -ExpandProperty IPAddress

Write-Host ''
Write-Host 'Waslora Resolver is running.' -ForegroundColor Green
Write-Host "Token: $Token"
if ($addresses) {
    foreach ($address in $addresses) { Write-Host "Address for the Android app: http://${address}:8787" }
} else {
    Write-Host 'Find the laptop IPv4 address and use http://<address>:8787 in the Android app.'
}
Write-Host 'Keep this token private. Stop it with: docker compose down'
