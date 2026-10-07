# Zips the world into backups\, keeping the newest -Keep of them. The world folder holds every Age, under
# dimensions\agesandtheart\. While the server runs, saving is paused over RCON for the copy and resumed after.
#
#   powershell -File backup.ps1                  one backup now (start.bat does this before every start)
#   powershell -File backup.ps1 -Register        also back up every day at -At (default 04:00)
param(
    [switch]$Register,
    [string]$At = '04:00',
    [int]$Keep = 14
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

if ($Register) {
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Keep $Keep" -WorkingDirectory $PSScriptRoot
    $trigger = New-ScheduledTaskTrigger -Daily -At $At
    Register-ScheduledTask -TaskName 'Ages and the Art backup' -Action $action -Trigger $trigger -Force | Out-Null
    Write-Host "Backing up every day at $At."
}

function Read-Properties($path) {
    $properties = @{}
    if (Test-Path $path) {
        foreach ($line in Get-Content $path) {
            if ($line -match '^\s*([^#=]+?)\s*=(.*)$') { $properties[$Matches[1]] = $Matches[2] }
        }
    }
    $properties
}

function Read-Exactly($stream, [int]$count) {
    $buffer = New-Object byte[] $count
    $read = 0
    while ($read -lt $count) {
        $got = $stream.Read($buffer, $read, $count - $read)
        if ($got -le 0) { throw 'RCON closed the connection' }
        $read += $got
    }
    $buffer
}

# One RCON request and the id of its reply: -1 when a login is refused.
function Invoke-Rcon($stream, [int]$type, [string]$body) {
    $payload = [Text.Encoding]::ASCII.GetBytes($body)
    $length = 10 + $payload.Length
    $packet = New-Object byte[] (4 + $length)
    [BitConverter]::GetBytes([int]$length).CopyTo($packet, 0)
    [BitConverter]::GetBytes([int]1).CopyTo($packet, 4)
    [BitConverter]::GetBytes($type).CopyTo($packet, 8)
    $payload.CopyTo($packet, 12)
    $stream.Write($packet, 0, $packet.Length)
    $replyLength = [BitConverter]::ToInt32((Read-Exactly $stream 4), 0)
    [BitConverter]::ToInt32((Read-Exactly $stream $replyLength), 0)
}

function Connect-Rcon($properties) {
    if ($properties['enable-rcon'] -ne 'true' -or -not $properties['rcon.password']) { return $null }
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', [int]$properties['rcon.port'])
    } catch {
        return $null
    }
    $stream = $client.GetStream()
    if ((Invoke-Rcon $stream 3 $properties['rcon.password']) -eq -1) { throw 'RCON refused the password in server.properties' }
    @{ Client = $client; Stream = $stream }
}

$properties = Read-Properties 'server.properties'
$level = if ($properties['level-name']) { $properties['level-name'] } else { 'world' }
if (-not (Test-Path $level)) { exit 0 }

New-Item -ItemType Directory -Force -Path 'backups' | Out-Null
$target = Join-Path 'backups' ("$level-" + (Get-Date -Format 'yyyy-MM-dd-HHmmss') + '.zip')

$rcon = Connect-Rcon $properties
try {
    if ($rcon) {
        Invoke-Rcon $rcon.Stream 2 'save-off' | Out-Null
        Invoke-Rcon $rcon.Stream 2 'save-all flush' | Out-Null
    }
    $root = (Resolve-Path $level).Path
    $zip = [System.IO.Compression.ZipFile]::Open((Join-Path $PSScriptRoot $target), 'Create')
    try {
        foreach ($file in Get-ChildItem -Path $root -Recurse -File) {
            # The server holds this one locked, and it says nothing worth keeping.
            if ($file.Name -eq 'session.lock') { continue }
            $name = $level + '/' + $file.FullName.Substring($root.Length + 1).Replace('\', '/')
            $entry = $zip.CreateEntry($name, 'Optimal')
            # Shared for writing, since a running server keeps its region files open.
            $source = [System.IO.File]::Open($file.FullName, 'Open', 'Read', 'ReadWrite')
            $destination = $entry.Open()
            try { $source.CopyTo($destination) } finally { $destination.Dispose(); $source.Dispose() }
        }
    } finally {
        $zip.Dispose()
    }
} finally {
    if ($rcon) {
        Invoke-Rcon $rcon.Stream 2 'save-on' | Out-Null
        $rcon.Client.Close()
    }
}
Write-Host "Backed up $level to $target."

Get-ChildItem 'backups' -Filter "$level-*.zip" | Sort-Object Name -Descending | Select-Object -Skip $Keep |
    Remove-Item
