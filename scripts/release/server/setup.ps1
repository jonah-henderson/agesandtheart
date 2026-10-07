# First start only: writes server.properties from the beta's defaults with an RCON password of its own,
# and asks for the Minecraft EULA. start.bat runs this when there is no server.properties yet.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$alphabet = [char[]]'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$password = -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })

(Get-Content 'server.defaults.properties') -replace '^rcon.password=.*$', "rcon.password=$password" |
    Set-Content 'server.properties' -Encoding ascii
Write-Host 'Wrote server.properties. RCON listens on this machine only: never forward its port.'

if (-not (Test-Path 'eula.txt') -or -not (Select-String -Path 'eula.txt' -Pattern '^eula=true' -Quiet)) {
    Write-Host ''
    Write-Host 'Minecraft''s EULA: https://aka.ms/MinecraftEULA'
    $answer = Read-Host 'Do you agree to it? Type yes to continue'
    if ($answer -ne 'yes') { Write-Host 'Not started.'; exit 1 }
    Set-Content 'eula.txt' 'eula=true' -Encoding ascii
}
