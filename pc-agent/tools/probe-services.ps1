[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$svcs = @(Get-Service | ForEach-Object {
  $s = $_
  $startType = $null
  try { $startType = $s.StartType.ToString() } catch { }
  [PSCustomObject]@{
    name        = $s.Name
    displayName = $s.DisplayName
    status      = $s.Status.ToString()
    startType   = $startType
    canStop     = [bool]$s.CanStop
  }
})

$out = [PSCustomObject]@{
  total    = @($svcs).Count
  captured = [datetime]::UtcNow.ToString('o')
  items    = $svcs
}

$out | ConvertTo-Json -Depth 4 -Compress | Out-File -FilePath '$env:TEMP\termdesk-svcs.json' -Encoding utf8
Write-Output "written"
