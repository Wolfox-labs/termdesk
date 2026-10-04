param([switch]$EnableShell)
$ErrorActionPreference = 'Stop'
$script = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'run-agent.ps1'))
$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$args = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`""
if ($EnableShell) { $args += ' -EnableShell' }
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $args
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName 'TermDesk Agent' -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Description 'Local TermDesk kernel with reverse VPS transport and restart supervision' -Force | Out-Null
Start-ScheduledTask -TaskName 'TermDesk Agent'
Write-Output 'TermDesk Agent installed: login startup, crash restart, loopback-only listener.'
