param([switch]$EnableShell)
$ErrorActionPreference = 'Stop'
$AgentRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\pc-agent'))
$Node = (Get-Command node.exe).Source
$State = Join-Path $env:USERPROFILE '.termdesk'
New-Item -ItemType Directory -Path $State -Force | Out-Null
$Log = Join-Path $State 'agent.log'
$entry = Join-Path $AgentRoot 'src\server.js'
$lockPath = Join-Path $State 'agent.lock'
try { $lock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
catch { exit 0 }
try {
    while ($true) {
        if ((Test-Path -LiteralPath $Log) -and (Get-Item -LiteralPath $Log).Length -gt 5MB) {
            Move-Item -LiteralPath $Log -Destination "$Log.previous" -Force
        }
        $args = @($entry, '--host', '127.0.0.1')
        if ($EnableShell) { $args += '--enable-shell' }
        # This supervisor itself is launched hidden at logon; never expose an interactive window.
        & $Node @args *>> $Log
        Start-Sleep -Seconds 5
    }
} finally { $lock.Dispose() }
