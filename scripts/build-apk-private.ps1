# 私有产物出包：与统一入口同 JVM 参数（同 daemon），但把 APK 落到私有路径
# 用途：多会话共享 app/build 时，避免自己出包覆盖别会话正在验收的产物
# 用法：pwsh -NoProfile -File scripts/build-apk-private.ps1 -Out _logs\lg-aod.apk
#   -QueueStatus 看持锁者｜-NoWait 拿不到锁立刻退｜-QueueTimeoutMin 排队上限（默认 60）
param(
    [string]$Out = "_logs\lg-private.apk",
    [string]$Task = ":app:assembleRelease",
    [switch]$QueueStatus,
    [switch]$NoWait,
    [int]$QueueTimeoutMin = 60
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
# ★ 与 build-apk.ps1 共用同一把全局锁：private 不是旁路，一样排队
. (Join-Path $PSScriptRoot "build-queue.ps1")
if ($QueueStatus) { Show-BuildQueueStatus; return }
$GradleVersion = "8.14.2"
$GradleBat = Get-ChildItem -Path (Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-$GradleVersion-bin") -Filter "gradle.bat" -Recurse -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $GradleBat) { throw "未找到 gradle $GradleVersion wrapper 缓存" }

# JVM 参数与 scripts/build-apk.ps1 保持逐字一致，否则会另起 daemon
$JvmArgs = "-Xmx2g -XX:MaxMetaspaceSize=768m -XX:+UseG1GC -Dfile.encoding=UTF-8"
$KotlinArgs = "-Xmx2g"

Enter-BuildQueue -Task $Task -Root $root -NoWait:$NoWait -TimeoutMin $QueueTimeoutMin | Out-Null
Push-Location $root
try {
    $args_ = @(
        "-Dorg.gradle.jvmargs=$JvmArgs",
        "-Pkotlin.daemon.jvmargs=$KotlinArgs",
        "--console=plain"
    ) + ($Task -split ' ')
    & $GradleBat @args_
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $src = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $src)) { throw "产物不存在: $src" }
    $dst = Join-Path $root $Out
    $dstDir = Split-Path -Parent $dst
    if (-not (Test-Path $dstDir)) { New-Item -ItemType Directory -Force $dstDir | Out-Null }
    Copy-Item $src $dst -Force
    $md5 = (Get-FileHash $dst -Algorithm MD5).Hash.ToLower()
    Write-Output "PRIVATE_APK=$dst"
    Write-Output "MD5=$md5"
} finally { Pop-Location; Exit-BuildQueue }