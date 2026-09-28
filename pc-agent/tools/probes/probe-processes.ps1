[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$procs = Get-Process | ForEach-Object {
  $p = $_
  $start = $null
  try { $start = $p.StartTime.ToUniversalTime().ToString('o') } catch { }
  $cpu = $null
  try { if ($null -ne $p.CPU) { $cpu = [math]::Round([double]$p.CPU, 2) } } catch { }
  $threads = 0
  try { $threads = $p.Threads.Count } catch { }
  [PSCustomObject]@{
    pid        = $p.Id
    name       = $p.ProcessName
    cpuSeconds = $cpu
    memBytes   = $p.WorkingSet64
    startTime  = $start
    threads    = $threads
  }
}

$procs = $procs | Sort-Object -Property memBytes -Descending

$out = [PSCustomObject]@{
  total    = @($procs).Count
  captured = [datetime]::UtcNow.ToString('o')
  items    = @($procs | Select-Object -First 400)
}

$out | ConvertTo-Json -Depth 4 -Compress | Out-File -FilePath '$env:TEMP\termdesk-procs.json' -Encoding utf8
Write-Output "written"
