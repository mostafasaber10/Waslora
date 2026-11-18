param(
    [string]$AdministratorEmail = $env:TATA_STORE_ADMIN_EMAIL,
    [string]$AdministratorSetupToken = $env:TATA_STORE_ADMIN_SETUP_TOKEN,
    [string]$BindAddress = '127.0.0.1',
    [int]$StorePort = 8840
)

$ErrorActionPreference = 'Stop'
if ($AdministratorEmail -notmatch '^[^\s@]+@[^\s@]+\.[^\s@]+$') {
    throw 'Set TATA_STORE_ADMIN_EMAIL or pass -AdministratorEmail with the store administrator account email.'
}
if (-not $AdministratorSetupToken -or $AdministratorSetupToken.Length -lt 32) {
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $tokenBytes = New-Object byte[] 32
    $random.GetBytes($tokenBytes)
    $random.Dispose()
    $AdministratorSetupToken = [Convert]::ToBase64String($tokenBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    Write-Host 'One-time store administrator setup key (enter it when creating or claiming the admin account):' -ForegroundColor Yellow
    Write-Host $AdministratorSetupToken -ForegroundColor Cyan
}
if ($StorePort -lt 1 -or $StorePort -gt 65535) { throw 'StorePort must be between 1 and 65535.' }

$env:TATA_STORE_ADMIN_EMAIL = $AdministratorEmail.Trim().ToLowerInvariant()
$env:TATA_STORE_ADMIN_SETUP_TOKEN = $AdministratorSetupToken
$env:HOST = $BindAddress
$env:PORT = [string]$StorePort
Push-Location $PSScriptRoot
try { node .\server.js }
finally { Pop-Location }
