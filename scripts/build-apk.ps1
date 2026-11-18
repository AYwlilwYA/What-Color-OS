# 统一构建入口：所有会话共用同一 Gradle/Kotlin daemon（JVM 参数唯一来源在此）
# 用法：
#   pwsh -NoProfile -File scripts/build-apk.ps1                      # 主仓 assembleRelease
#   pwsh -NoProfile -File scripts/build-apk.ps1 -Task ":app:testReleaseUnitTest --tests com.x.Y"
#   pwsh -NoProfile -File scripts/build-apk.ps1 -Worktree <路径>     # 临时工作树（同参数=同 daemon）
#   pwsh -NoProfile -File scripts/build-apk.ps1 -Daemons             # 列出当前 Gradle/Kotlin daemon
#   pwsh -NoProfile -File scripts/build-apk.ps1 -StopDaemons         # 停掉本版本全部 daemon（收工清理）
param(
    [string]$Task = ":app:assembleRelease",
    [string]$Worktree = "",
    [switch]$Daemons,
    [switch]$StopDaemons
)
$ErrorActionPreference = "Stop"
# gradle 版本与 wrapper 声明保持一致；从本机 wrapper 缓存定位，不写死个人路径
$GradleVersion = "8.14.2"
$GradleBat = Get-ChildItem -Path (Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-$GradleVersion-bin") -Filter "gradle.bat" -Recurse -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $GradleBat) { throw "未找到 gradle $GradleVersion wrapper 缓存，请先跑一次 wrapper 下载" }
# ★ 全仓唯一 JVM 参数来源：改这里必须广播所有会话；禁止在 gradle.properties / CLI 私改
$JvmArgs = "-Xmx2g -XX:MaxMetaspaceSize=768m -XX:+UseG1GC -Dfile.encoding=UTF-8"
$KotlinArgs = "-Xmx2g"

if ($Daemons) {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
        $c = $_.CommandLine
        $kind = if ($c -match 'GradleDaemon (\S+)') { "GradleDaemon $($Matches[1])" }
                elseif ($c -match 'KotlinCompileDaemon') { 'KotlinDaemon' } else { $null }
        if ($kind) {
            $xmx = if ($c -match '-Xmx(\S+)') { $Matches[1] } else { '-' }
            "{0}  {1}  xmx={2}  ws={3}MB" -f $_.ProcessId, $kind, $xmx, [math]::Round($_.WorkingSetSize/1MB)
        }
    }
    return
}

$root = if ($Worktree) { $Worktree } else { Split-Path -Parent $PSScriptRoot }
Push-Location $root
try {
    if ($StopDaemons) { & $GradleBat --stop; return }
    # -D 覆盖 properties：无论各工作树 gradle.properties 写了什么，实际 JVM 参数恒为上面唯一值
    $args_ = @(
        "-Dorg.gradle.jvmargs=$JvmArgs",
        "-Pkotlin.daemon.jvmargs=$KotlinArgs",
        "--console=plain"
    ) + ($Task -split ' ')
    & $GradleBat @args_
    exit $LASTEXITCODE
} finally { Pop-Location }
