# 全局构建排队锁（唯一实现）：所有构建入口 dot-source 本文件后调用 Enter-BuildQueue / Exit-BuildQueue
# 同一时刻全机只允许一个 Gradle 构建，其余排队；锁放用户级，跨仓库跨工作树共用一把
# 单文件锁：文件内容即持锁者信息，消除「锁已建、owner 未写」的窗口

# 锁目录：默认全机共用；BQ_TEST_DIR 仅供锁机制自测隔离，正常构建不许设
$script:BQ_Dir = if ($env:BQ_TEST_DIR) { $env:BQ_TEST_DIR } else { Join-Path $env:LOCALAPPDATA "gradle-build-queue" }
$script:BQ_Lock = Join-Path $script:BQ_Dir "build.lock"
$script:BQ_Stream = $null
$script:BQ_Pid = 0

function Get-BuildQueueOwner {
    if (-not (Test-Path $script:BQ_Lock)) { return $null }
    try {
        $t = Get-Content $script:BQ_Lock -Raw -ErrorAction Stop
        if ([string]::IsNullOrWhiteSpace($t)) { return $null }
        return $t | ConvertFrom-Json
    } catch { return $null }
}

function Show-BuildQueueStatus {
    if (-not (Test-Path $script:BQ_Lock)) { "当前无人持锁（空闲）"; return }
    $o = Get-BuildQueueOwner
    if ($o) {
        $alive = [bool](Get-Process -Id $o.pid -ErrorAction SilentlyContinue)
        "持锁者 pid={0} task={1} root={2} since={3} 存活={4}" -f $o.pid, $o.task, $o.root, $o.since, $alive
    } else { "锁文件存在但内容未写完（持有者刚取锁，视为占用）" }
}

# 取锁。成功返回 $true；-NoWait 拿不到 exit 75；超时 exit 76
function Enter-BuildQueue {
    param([string]$Task = "", [string]$Root = "", [switch]$NoWait, [int]$TimeoutMin = 60)
    if (-not (Test-Path $script:BQ_Dir)) { New-Item -ItemType Directory -Force -Path $script:BQ_Dir | Out-Null }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $waited = $false
    $staleTry = 0
    while ($true) {
        try {
            $script:BQ_Stream = [System.IO.File]::Open($script:BQ_Lock, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read)
            break
        } catch [System.IO.IOException] {
            $o = Get-BuildQueueOwner
            $who = if ($o) { "pid=$($o.pid) task=$($o.task)" } else { "持锁者信息未写完" }
            # 陈旧锁：owner 记的进程已死；或内容空且静置 >120s（用 LastWriteTime，CreationTime 受 Windows tunneling 影响不可信）
            $isStale = $false
            if ($o) {
                $isStale = -not (Get-Process -Id $o.pid -ErrorAction SilentlyContinue)
            } else {
                $li = Get-Item $script:BQ_Lock -ErrorAction SilentlyContinue
                if ($li) { $isStale = ((Get-Date) - $li.LastWriteTime).TotalSeconds -gt 120 }
            }
            # 清理最多 3 次；删不掉退化成普通排队，每轮必过超时闸，绝不空转
            if ($isStale -and $staleTry -lt 3) {
                $staleTry++
                Write-Host "[queue] $who 判为陈旧锁，清理（第 $staleTry 次）"
                Remove-Item $script:BQ_Lock -Force -ErrorAction SilentlyContinue
                if (-not (Test-Path $script:BQ_Lock)) { continue }
                Write-Host "[queue] 陈旧锁删不掉（被占用/无权限），按活锁排队"
            }
            if ($NoWait) { Write-Host "[queue] 已有构建在跑（$who），-NoWait 立即退出"; exit 75 }
            if ($sw.Elapsed.TotalMinutes -ge $TimeoutMin) {
                Write-Host "[queue] 排队超过 $TimeoutMin 分钟仍无锁，放弃（$who）"; exit 76
            }
            if (-not $waited) { Write-Host "[queue] 已有构建在跑（$who），排队等待…"; $waited = $true }
            Start-Sleep -Seconds 5
        }
    }
    $script:BQ_Pid = $PID
    $json = @{ pid = $PID; task = $Task; root = $Root; since = (Get-Date).ToString("s") } | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $script:BQ_Stream.Write($bytes, 0, $bytes.Length)
    $script:BQ_Stream.Flush()
    if ($waited) {
        Write-Host ("[queue] 拿到锁，等待 {0:N1} 分钟" -f $sw.Elapsed.TotalMinutes)
        Clear-OrphanGradleClient
    }
    return $true
}

# 收掉孤儿 gradle 客户端：识别按实测命令行 gradle-gradle-cli-main（-Xmx64m 那个）
# daemon 的父进程本就常死（实测 GradleDaemon par 已退出仍在服务），绝不按父死判 daemon
# 只在真排队过时才调（无竞争路径零开销）；CIM 查一次约 4s，排队本身是分钟级，可接受
# 判活看整条祖先链：链上出现构建入口 pwsh 即认活（中间 cmd 死了不算孤儿）
function Clear-OrphanGradleClient {
    if (-not (Get-Process -Name java -ErrorAction SilentlyContinue)) { return }
    $all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
    $byPid = @{}
    foreach ($p in $all) { $byPid[[int]$p.ProcessId] = $p }
    foreach ($x in $all) {
        if ($x.Name -ne 'java.exe') { continue }
        if ([string]$x.CommandLine -notmatch 'gradle-gradle-cli-main') { continue }
        $cur = [int]$x.ParentProcessId
        $hasOwner = $false
        for ($d = 0; $d -lt 12 -and $cur -gt 0; $d++) {
            $par = $byPid[$cur]
            if (-not $par) { break }
            if ($par.Name -match 'pwsh|powershell' -and [string]$par.CommandLine -match 'build-apk') { $hasOwner = $true; break }
            $cur = [int]$par.ParentProcessId
        }
        if ($hasOwner) { continue }
        Write-Host "[queue] 收掉孤儿 gradle 客户端 pid=$($x.ProcessId)（祖先链上无构建入口）"
        Stop-Process -Id $x.ProcessId -Force -Confirm:$false -ErrorAction SilentlyContinue
    }
}

function Exit-BuildQueue {
    if ($script:BQ_Stream) { $script:BQ_Stream.Close(); $script:BQ_Stream.Dispose(); $script:BQ_Stream = $null }
    # 只删自己那把：内容读不到或已被他人接管都不删，交陈旧锁判定处理
    $o = Get-BuildQueueOwner
    if ($o -and $o.pid -eq $script:BQ_Pid) {
        Remove-Item $script:BQ_Lock -Force -ErrorAction SilentlyContinue
    }
}
