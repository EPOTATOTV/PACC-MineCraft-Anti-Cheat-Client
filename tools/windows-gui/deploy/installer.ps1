﻿# PACC Windows 客户端安装编排脚本
#
# 调用方：
#   - 安装包（Inno Setup，见 tools/installer/pacc-client-installer.iss）：安装后经 [Run] 调用，
#     卸载时经 [UninstallRun] 调用（-Uninstall）。
#   - PaccManager GUI 的「安装 / 修复」按钮，或手动执行。
#
# 独立运行示例：
#   powershell -ExecutionPolicy Bypass -File tools/windows-gui/deploy/installer.ps1 `
#       -InstallDir "$env:ProgramFiles\PACC" -DataDir "$env:ProgramData\PACC"
#
# 说明：EV 代码签名、WHQL 认证属外部事务，本脚本不依赖签名结果；
#       缺少内核驱动 inf 时只告警、不失败（内核组件为可选）。
#       在未取得 WHQL 认证前，可用测试签名驱动：指定 -EnableTestSigning 打开测试签名模式
#       （bcdedit /set testsigning on，需重启），仅在测试机使用。

[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $env:ProgramFiles "PACC"),
    [string]$DataDir    = (Join-Path $env:ProgramData  "PACC"),
    [switch]$SkipDriver,
    [switch]$SkipAutostart,
    [switch]$EnableTestSigning,
    [switch]$Uninstall
)

$ErrorActionPreference = "Stop"

# 内核组件契约：与 platform/kernel-windows 的服务名 / 设备名保持一致
$DriverServiceName = "PaccPtv"
$DriverDeviceName  = "\\.\PaccPtv"
$RunKeyPath        = "HKLM:\Software\Microsoft\Windows\CurrentVersion\Run"
$RunValueName      = "PACCClient"
$MainExeName       = "PACCClient.exe"
# Inno Setup 写入的卸载登记项（AppId 见 pacc-client-installer.iss，双花括号是 Inno 转义）
$UninstallRegKey   = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{8F3C1E52-7C24-4B6E-9A0D-4E5B2C6D8F01}"

$script:LogPath = $null

function Write-Log {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [ValidateSet("INFO", "OK", "WARN", "ERROR")][string]$Level = "INFO"
    )
    $line = "[{0}][{1}] {2}" -f (Get-Date).ToString("yyyy-MM-dd HH:mm:ss"), $Level, $Message
    $color = switch ($Level) {
        "OK"    { "Green" }
        "WARN"  { "Yellow" }
        "ERROR" { "Red" }
        default { "Gray" }
    }
    Write-Host $line -ForegroundColor $color
    if ($script:LogPath) {
        try { Add-Content -Path $script:LogPath -Value $line -Encoding UTF8 } catch { }
    }
}

# 写无 BOM 的 UTF-8：Windows PowerShell 5.1 的 -Encoding UTF8 会带 BOM，
# 而 Java Properties 不剥 BOM，会把首个键读成 "\uFEFFpacc.client.endpoint"。
function Write-TextNoBom {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Text)
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Text, $enc)
}

function Test-Administrator {
    $identity  = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Initialize-Logging {
    # 日志统一落 DataDir\logs\install.log（安装与卸载都写，便于事后追溯）
    $logDir = Join-Path $DataDir "logs"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $script:LogPath = Join-Path $logDir "install.log"
}

function Test-Prechecks {
    Write-Log "环境预检开始"

    # ---- Windows 版本 ----
    # Win10 与 Win11 都报告为 10.0，只能靠 build 号区分；1809 = 17763。
    $osv   = [Environment]::OSVersion.Version
    $build = $osv.Build
    if ($build -lt 0) {
        try { $build = [int](Get-CimInstance Win32_OperatingSystem).BuildNumber } catch { }
    }
    if ($osv.Major -lt 10 -or ($osv.Major -eq 10 -and $build -lt 17763)) {
        throw ("需要 Windows 10 1809（内部版本 17763）或更高；当前 {0}.{1} (build {2})" -f $osv.Major, $osv.Minor, $build)
    }
    Write-Log ("Windows 版本满足要求：{0}.{1} build {2}" -f $osv.Major, $osv.Minor, $build) OK

    # ---- 架构 ----
    # 32 位宿主进程要读 PROCESSOR_ARCHITEW6432 才是真实架构
    $arch = $env:PROCESSOR_ARCHITECTURE
    if ($env:PROCESSOR_ARCHITEW6432) { $arch = $env:PROCESSOR_ARCHITEW6432 }
    if ($arch -ne "AMD64" -and $arch -ne "ARM64") {
        throw ("仅支持 64 位 Windows（x64 / ARM64）；当前架构 {0}" -f $arch)
    }
    Write-Log ("系统架构满足要求：{0}" -f $arch) OK

    # ---- 磁盘可用空间（>= 500 MB）----
    # 读盘符失败只告警；空间不足必须致命，所以两者拆开，别让 catch 把致命判断吞掉。
    $qualifier = [System.IO.Path]::GetPathRoot($InstallDir).TrimEnd("\").TrimEnd(":")
    try {
        $freeMB = [Math]::Round((Get-PSDrive -Name $qualifier).Free / 1MB, 0)
    } catch {
        $freeMB = $null
        Write-Log ("无法读取安装分区（{0}）可用空间，跳过该检查" -f $qualifier) WARN
    }
    if ($null -ne $freeMB) {
        if ($freeMB -lt 500) {
            throw ("安装分区可用空间不足：{0} MB（至少需要 500 MB）" -f $freeMB)
        }
        Write-Log ("磁盘可用空间满足要求：{0} MB" -f $freeMB) OK
    }

    # ---- 旧版本探测（提示升级）----
    if (Test-Path $UninstallRegKey) {
        Write-Log "检测到已登记安装的旧版本，本次将覆盖升级" WARN
    } elseif (Test-Path (Join-Path $InstallDir $MainExeName)) {
        Write-Log ("安装目录存在旧版本（{0}），本次将覆盖升级" -f $InstallDir) WARN
    } else {
        Write-Log "未检测到旧版本，执行全新安装" OK
    }

    # ---- Java 运行时（告警不致命）----
    # 桌面壳由安装包声明依赖并自带 WebView 运行时，不强制 JRE；探针则需要 JRE 21+。
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        Write-Log "未检测到 Java 运行时。桌面壳不依赖 JRE；如需玩家端探针，请另行安装 JRE 21+" WARN
    } else {
        $saved = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try { $verText = (& java -version 2>&1) -join "`n" } finally { $ErrorActionPreference = $saved }
        if ($verText -match 'version "(\d+)') {
            $major = [int]$Matches[1]
            if ($major -lt 21) {
                Write-Log ("检测到 Java {0}，低于 21；玩家端探针需要 JRE 21+" -f $major) WARN
            } else {
                Write-Log ("Java 运行时满足要求：{0}" -f $major) OK
            }
        } else {
            Write-Log "无法解析 java -version 输出，跳过 Java 检查" WARN
        }
    }

    Write-Log "环境预检通过" OK
}

function New-DefaultConfig {
    $cfg = Join-Path $DataDir "pacc-client.properties"
    if (Test-Path $cfg) {
        Write-Log ("配置基线已存在，保留现有配置：{0}" -f $cfg)
        return
    }
    $content = @"
# PACC 客户端配置基线（由安装器生成）
# 修改后需重启 PACC 客户端生效。
pacc.client.endpoint=wss://pacc.potatotv.asia/ws/ptv
pacc.client.api-base=https://api.potatotv.asia
pacc.detection.redscreen-threshold=85
pacc.detection.sample-rate=1.0
pacc.log.level=INFO
pacc.client.driver.enabled=true
pacc.client.keyboard-lock.enabled=true
"@
    Write-TextNoBom -Path $cfg -Text $content
    Write-Log ("已生成默认配置基线：{0}" -f $cfg) OK
}

function Get-TestSigningState {
    # 读取当前启动项的 testsigning 状态；返回 $true/$false/$null（无法判定）
    try {
        $out = & bcdedit.exe /enum "{current}" 2>$null
        foreach ($line in $out) {
            if ($line -match '^\s*testsigning\s+(Yes|No)') { return ($Matches[1] -eq 'Yes') }
        }
        return $false
    } catch {
        return $null
    }
}

function Install-Driver {
    if ($SkipDriver) {
        Write-Log "已指定 -SkipDriver，跳过内核组件安装" WARN
        return
    }
    $inf = Join-Path $InstallDir "drivers\PaccPtv.inf"
    if (-not (Test-Path $inf)) {
        Write-Log ("未发现内核驱动 inf（{0}），跳过内核组件安装（可选组件，不算失败）" -f $inf) WARN
        return
    }

    # WHQL 认证前的过渡：显式开启测试签名模式，测试签名的 .sys 才能被加载。
    # 会修改本机启动配置且需重启，仅测试机使用。
    if ($EnableTestSigning) {
        Write-Log "启用测试签名模式（bcdedit /set testsigning on）——仅测试机使用" WARN
        & bcdedit.exe /set testsigning on | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Log "已启用测试签名；需重启后生效。正式发布请改用 EV 签名 + WHQL 认证。" WARN
        } else {
            Write-Log ("bcdedit 返回 {0}（可能未关闭安全启动 / 非管理员）" -f $LASTEXITCODE) WARN
        }
    }

    Write-Log ("安装内核驱动：{0}" -f $inf)
    & pnputil.exe /add-driver $inf /install
    if ($LASTEXITCODE -ne 0) {
        Write-Log ("pnputil /add-driver 返回 {0}（驱动可能未签名 / 未通过 WHQL，交由上层处理）" -f $LASTEXITCODE) WARN
    }

    $svc = Get-Service -Name $DriverServiceName -ErrorAction SilentlyContinue
    if ($null -eq $svc) {
        # sc.exe 必须带扩展名：PowerShell 里 sc 是 Set-Content 的别名
        & sc.exe create $DriverServiceName type= kernel binPath= "system32\drivers\PaccPtv.sys" start= demand | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Log ("sc create 返回 {0}" -f $LASTEXITCODE) WARN }
    } else {
        Write-Log ("驱动服务 {0} 已存在，跳过创建" -f $DriverServiceName)
    }

    & sc.exe start $DriverServiceName | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Log ("sc start 返回 {0}（可能已在运行，或驱动未签名被拒）" -f $LASTEXITCODE) WARN
        # 驱动启动失败且当前未开测试签名时，给出测试签名加载指引
        if (-not $EnableTestSigning -and (Get-TestSigningState) -ne $true) {
            Write-Log "若该驱动为测试签名：请以管理员执行 bcdedit /set testsigning on 后重启，再运行安装（或加 -EnableTestSigning）。" WARN
        }
    } else {
        Write-Log ("内核驱动服务 {0} 已启动（设备 {1}）" -f $DriverServiceName, $DriverDeviceName) OK
    }
}

function Install-Autostart {
    if ($SkipAutostart) {
        Write-Log "已指定 -SkipAutostart，跳过开机自启注册" WARN
        return
    }
    $exe = Join-Path $InstallDir $MainExeName
    if (-not (Test-Path $exe)) {
        Write-Log ("未找到主程序 {0}，跳过开机自启注册" -f $exe) WARN
        return
    }
    $cmd = '"{0}" --auto-start' -f $exe
    New-Item -Path $RunKeyPath -Force | Out-Null
    Set-ItemProperty -Path $RunKeyPath -Name $RunValueName -Value $cmd
    Write-Log ("已注册开机自启：{0} = {1}" -f $RunValueName, $cmd) OK
}

function Invoke-Uninstall {
    Write-Log "开始卸载 PACC Windows 客户端"

    # 1) 结束运行中的 UI 进程（主壳 / 诊断工具）
    foreach ($name in @("PACCClient", "PaccManager")) {
        $proc = Get-Process -Name $name -ErrorAction SilentlyContinue
        if ($proc) {
            $proc | Stop-Process -Force -ErrorAction SilentlyContinue
            Write-Log ("已结束进程：{0}" -f $name)
        }
    }

    # 2) 停止并删除内核驱动服务
    $svc = Get-Service -Name $DriverServiceName -ErrorAction SilentlyContinue
    if ($svc) {
        Stop-Service -Name $DriverServiceName -Force -ErrorAction SilentlyContinue
        & sc.exe delete $DriverServiceName | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Log ("已删除驱动服务：{0}" -f $DriverServiceName)
        } else {
            Write-Log ("sc delete 返回 {0}" -f $LASTEXITCODE) WARN
        }
    } else {
        Write-Log ("未发现驱动服务 {0}，跳过" -f $DriverServiceName)
    }

    # 3) 从驱动仓库移除（inf 可能已随文件删除而不存在，缺失不视为失败）
    $inf = Join-Path $InstallDir "drivers\PaccPtv.inf"
    if (Test-Path $inf) {
        & pnputil.exe /delete-driver $inf /uninstall
        if ($LASTEXITCODE -ne 0) {
            Write-Log ("pnputil /delete-driver 返回 {0}（驱动注册可能已不存在）" -f $LASTEXITCODE) WARN
        } else {
            Write-Log "已从驱动仓库移除 PaccPtv"
        }
    }

    # 4) 移除开机自启项
    if (Test-Path $RunKeyPath) {
        Remove-ItemProperty -Path $RunKeyPath -Name $RunValueName -ErrorAction SilentlyContinue
        Write-Log ("已移除开机自启项：{0}" -f $RunValueName)
    }

    # 5) 数据目录按设计保留，绝不删除（证据 / 日志 / control.json / ws-credentials.json）
    Write-Log ("证据与日志数据目录按设计保留，未删除：{0}" -f $DataDir)
    Write-Log "卸载完成" OK
}

# ============================ 入口 ============================

if (-not (Test-Administrator)) {
    throw "需要以管理员身份运行（安装 / 卸载均需提升权限）。"
}
Initialize-Logging

if ($Uninstall) {
    Write-Log ("== PACC 客户端卸载（InstallDir={0}, DataDir={1}）==" -f $InstallDir, $DataDir)
    Invoke-Uninstall
    return
}

Write-Log ("== PACC 客户端安装（InstallDir={0}, DataDir={1}）==" -f $InstallDir, $DataDir)

Test-Prechecks

# 目录结构：安装目录下的 bin / drivers / deploy，以及数据目录
New-Item -ItemType Directory -Force -Path `
    (Join-Path $InstallDir "bin"), `
    (Join-Path $InstallDir "drivers"), `
    (Join-Path $InstallDir "deploy"), `
    $DataDir | Out-Null
Write-Log "已创建安装目录结构"

New-DefaultConfig
Install-Driver
Install-Autostart

Write-Log "安装完成" OK