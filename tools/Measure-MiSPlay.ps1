# Measure-MiSPlay.ps1 — Windows PowerShell 5.1+ process baseline
# Read-only: samples the already-running application; does not touch the music library.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidateRange(1,2147483647)]
    [int]$TargetPid,

    [Parameter(Mandatory=$true)]
    [ValidateSet('Idle','Playback','Browsing','Import','Artwork','Custom')]
    [string]$Scenario,

    [ValidateRange(10,3600)]
    [int]$DurationSeconds = 60,

    [ValidateRange(1,60)]
    [int]$IntervalSeconds = 2,

    [ValidateRange(0,300)]
    [int]$WarmupSeconds = 5,

    [string]$OutputDirectory = '.\baseline'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$target = Get-Process -Id $TargetPid -ErrorAction SilentlyContinue
if ($null -eq $target) { throw "PID $TargetPid was not found. Start MiSPlay before measuring." }
if ($target.ProcessName -notmatch '^(java|javaw|MiSPlay)$') {
    Write-Warning "PID $TargetPid belongs to '$($target.ProcessName)'. Verify this is your player, not IntelliJ or Maven."
}
if ($WarmupSeconds -gt 0) {
    Write-Host "Settling for $WarmupSeconds second(s)..."
    Start-Sleep -Seconds $WarmupSeconds
}

$target = Get-Process -Id $TargetPid -ErrorAction SilentlyContinue
if ($null -eq $target) { throw 'MiSPlay exited during warmup.' }
$previousCpu = $target.TotalProcessorTime.TotalSeconds
$previousTime = [DateTime]::UtcNow
$start = $previousTime
$logicalProcessors = [Environment]::ProcessorCount
$rows = New-Object 'System.Collections.Generic.List[object]'
$samples = [int][Math]::Ceiling($DurationSeconds / [double]$IntervalSeconds)
Write-Host "Recording ${Scenario}: $samples samples at $IntervalSeconds-second intervals for PID $TargetPid"

for ($i = 0; $i -lt $samples; $i++) {
    Start-Sleep -Seconds $IntervalSeconds
    $now = [DateTime]::UtcNow
    $process = Get-Process -Id $TargetPid -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        Write-Warning 'MiSPlay exited; saving partial observations.'
        break
    }
    $cpuSeconds = $process.TotalProcessorTime.TotalSeconds
    $seconds = ($now - $previousTime).TotalSeconds
    $normalizedCpuPercent = if ($seconds -gt 0) {
        [Math]::Max(0.0, (($cpuSeconds - $previousCpu) / $seconds / $logicalProcessors) * 100.0)
    } else { 0.0 }
    $rows.Add([pscustomobject]@{
        Timestamp = $now.ToLocalTime().ToString('o')
        Scenario = $Scenario
        PID = $TargetPid
        ElapsedSeconds = [Math]::Round(($now - $start).TotalSeconds, 2)
        CPUPercentOfMachine = [Math]::Round($normalizedCpuPercent, 2)
        WorkingSetMB = [Math]::Round($process.WorkingSet64 / 1MB, 2)
        PrivateCommitMB = [Math]::Round($process.PrivateMemorySize64 / 1MB, 2)
        ThreadCount = $process.Threads.Count
        HandleCount = $process.HandleCount
    })
    $previousCpu = $cpuSeconds
    $previousTime = $now
}
if ($rows.Count -eq 0) { throw 'No observations were collected.' }

$resolvedDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedDir -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$csv = Join-Path $resolvedDir "$($stamp)_$($Scenario)_pid$TargetPid.csv"
$rows | Export-Csv -Path $csv -NoTypeInformation -Encoding UTF8
$cpu = $rows | Measure-Object -Property CPUPercentOfMachine -Average -Maximum
$working = $rows | Measure-Object -Property WorkingSetMB -Average -Maximum
$private = $rows | Measure-Object -Property PrivateCommitMB -Average -Maximum

Write-Host ''
Write-Host "Saved samples: $csv"
Write-Host "Samples: $($rows.Count); logical processors: $logicalProcessors"
Write-Host ('CPU (share of whole machine), average / peak: {0:N2}% / {1:N2}%' -f $cpu.Average, $cpu.Maximum)
Write-Host ('Working set, average / peak: {0:N2} / {1:N2} MB' -f $working.Average, $working.Maximum)
Write-Host ('Private commit, average / peak: {0:N2} / {1:N2} MB' -f $private.Average, $private.Maximum)
Write-Host 'Working set is resident process memory (can include shared pages); private commit is private committed virtual memory, not heap-only RAM.'
