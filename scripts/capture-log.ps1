# 原始日志一键落盘：脚本内不做任何过滤，检索/分析由取日志的一方负责。
# -Seconds 0 = 抓当前缓冲区快照后立刻退出（不起流式进程）。
# 输出固定为绝对路径：<repo>/_logs/<yyyy-MM-dd-HHmmss>-<主题>.log
# 同时把本次路径写进 <repo>/_logs/LATEST.txt（分析方直接读，不需要用户回路径）。
# 不指定 -Device 时自动选真机（排除 emulator-*）；多台真机优先型号 PJZ110（主力机）。
param(
    [string]$Topic = 'run',
    [int]$Seconds = 60,
    [string]$Device = "",
    [switch]$Clear
)

$ErrorActionPreference = 'Stop'

# pwsh 的 PATH 里 Git 自带 adb 是 shell 脚本，不能直接调用 → 只认 .exe
$adb = $null
foreach ($c in (Get-Command adb -All -ErrorAction SilentlyContinue)) {
    if ($c.Source -like '*.exe') { $adb = $c.Source; break }
}
if (-not $adb) {
    foreach ($root in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if (-not $root) { continue }
        $p = Join-Path (Join-Path $root 'platform-tools') 'adb.exe'
        if (Test-Path -LiteralPath $p) { $adb = $p; break }
    }
}
if (-not $adb) {
    $fallback = 'E:/gongju/Android/Tools/platform-tools/adb.exe'
    if (Test-Path -LiteralPath $fallback) { $adb = $fallback }
}
if (-not $adb) { Write-Host 'ADB_MISSING: 找不到 adb.exe'; exit 1 }

$repoRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$logDir = [System.IO.Path]::GetFullPath((Join-Path $repoRoot '_logs'))
if (-not (Test-Path -LiteralPath $logDir)) {
    New-Item -ItemType Directory -Path $logDir | Out-Null
}

$safe = ($Topic -replace '[\\/:*?"<>|\s]', '_')
if ([string]::IsNullOrWhiteSpace($safe)) { $safe = 'run' }
$stamp = Get-Date -Format 'yyyy-MM-dd-HHmmss'
$out = [System.IO.Path]::GetFullPath((Join-Path $logDir ("{0}-{1}.log" -f $stamp, $safe)))
$latest = [System.IO.Path]::GetFullPath((Join-Path $logDir 'LATEST.txt'))

$target = $Device
if (-not $target) {
    $serials = @()
    foreach ($line in (& $adb devices)) {
        if ($line -match '^(\S+)\s+device\b') { $serials += $Matches[1] }
    }
    $real = @($serials | Where-Object { $_ -notlike 'emulator-*' })
    if ($real.Count -eq 1) {
        $target = $real[0]
    } elseif ($real.Count -gt 1) {
        foreach ($s in $real) {
            $model = ((& $adb -s $s shell getprop ro.product.model 2>$null) -join '').Trim()
            if ($model -eq 'PJZ110') { $target = $s; break }
        }
        if (-not $target) { $target = $real[0] }
    } else {
        Write-Host 'ADB_DEVICE_MISSING: 没找到真机（只剩模拟器或都掉线了），请插上主力机'
        exit 1
    }
    Write-Host ("DEVICE_AUTO={0}" -f $target)
}

$adbPrefix = @('-s', $target)

& $adb @adbPrefix get-state 1>$null 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host ("ADB_DEVICE_MISSING: 设备 {0} 不在线" -f $target)
    exit 1
}
if ($Clear) { & $adb @adbPrefix logcat -c 1>$null 2>$null }

Set-Content -LiteralPath $latest -Value $out -Encoding UTF8
Write-Host ("LOG_CAPTURING topic={0} seconds={1} clear={2} device={3}" -f $Topic, $Seconds, [bool]$Clear, $target)
Write-Host ("LOG_FILE={0}" -f $out)

if ($Seconds -le 0) {
    $args = @($adbPrefix) + @('logcat', '-b', 'all', '-v', 'threadtime', '-d')
    $proc = Start-Process -FilePath $adb -ArgumentList $args `
        -RedirectStandardOutput $out -NoNewWindow -PassThru
    $proc.WaitForExit()
} else {
    $args = @($adbPrefix) + @('logcat', '-b', 'all', '-v', 'threadtime')
    $proc = Start-Process -FilePath $adb -ArgumentList $args `
        -RedirectStandardOutput $out -NoNewWindow -PassThru
    Start-Sleep -Seconds $Seconds
    if ($proc -and -not $proc.HasExited) {
        try { $proc.Kill() } catch { }
    }
}

$bytes = (Get-Item -LiteralPath $out).Length
Write-Host ("LOG_DONE bytes={0}" -f $bytes)
Write-Host ("LOG_FILE={0}" -f $out)
