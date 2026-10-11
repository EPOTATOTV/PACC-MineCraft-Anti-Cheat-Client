# PACC 测试代码签名证书生成（EV / WHQL 认证前的过渡方案）
#
# 背景：正式发布需要微软 EV 代码签名证书 + 内核驱动 WHQL 认证，这两项都是外部事务。
#       在拿到之前，用自签名测试证书对 Windows 产物（exe / msi / dll / sys）做「测试签名」，
#       让内部测试机与 CI 能够验证安装、加载与升级流程。
#
# 重要：测试签名与 WHQL / EV 正式签名不等价，绝不能用于对外发布：
#   - 用户态程序测试签名后仍会触发 SmartScreen「未知发布者」提示；
#   - 内核驱动 .sys 必须把测试证书装入「受信任的根证书颁发机构」与「受信任的发布者」，
#     并在目标机执行 `bcdedit /set testsigning on` 后才会被加载；
#   - 正式对外发布前必须替换为 EV 证书并完成 WHQL 认证。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/gen-test-cert.ps1 `
#       -OutPfx dist\test-signing.pfx -Password "PaccTest!"
#   # 追加 -Trust 会把证书装入本机受信任存储（需管理员；仅测试机执行）
#   powershell -ExecutionPolicy Bypass -File scripts/gen-test-cert.ps1 `
#       -OutPfx dist\test-signing.pfx -Password "PaccTest!" -Trust

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutPfx,
    [Parameter(Mandatory = $true)][string]$Password,
    [string]$Subject = "CN=PACC Test Code Signing",
    [int]$ValidYears = 3,
    [switch]$Trust,
    [switch]$Force
)

$ErrorActionPreference = "Stop"

if ((Test-Path $OutPfx) -and -not $Force) {
    throw ("目标文件已存在：{0}（如需覆盖请加 -Force）" -f $OutPfx)
}

$outDir = Split-Path -Parent $OutPfx
if ($outDir -and -not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
}

Write-Host "生成自签名测试代码签名证书：$Subject" -ForegroundColor Cyan

# CodeSigningCert 用途的自签名证书，可用于 Authenticode（用户态）与测试模式驱动签名。
$cert = New-SelfSignedCertificate `
    -Type CodeSigningCert `
    -Subject $Subject `
    -FriendlyName "PACC Test Code Signing" `
    -CertStoreLocation "Cert:\CurrentUser\My" `
    -KeyUsage DigitalSignature `
    -KeyExportPolicy Exportable `
    -NotAfter (Get-Date).AddYears($ValidYears)

if ($null -eq $cert) {
    throw "New-SelfSignedCertificate 未返回证书"
}
Write-Host ("证书指纹：{0}" -f $cert.Thumbprint) -ForegroundColor Green

# 导出 PFX（含私钥），供 signtool /f /p 使用；PFX 属敏感文件，不要提交仓库。
$secure = ConvertTo-SecureString -String $Password -AsPlainText -Force
Export-PfxCertificate -Cert $cert -FilePath $OutPfx -Password $secure | Out-Null
Write-Host ("已导出 PFX：{0}" -f $OutPfx) -ForegroundColor Green

if ($Trust) {
    # 仅测试机使用：把证书放进本机受信任存储，测试签名的 .sys 才能被加载。
    # 需要管理员权限，且会修改本机证书存储，不要在生产机执行。
    $isAdmin = (New-Object Security.Principal.WindowsPrincipal(
        [Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $isAdmin) {
        Write-Warning "-Trust 需要管理员权限，跳过受信任存储导入"
    } else {
        foreach ($store in @("Root", "TrustedPublisher")) {
            $path = "Cert:\LocalMachine\$store"
            Export-Certificate -Cert $cert -FilePath (Join-Path $env:TEMP "pacc-test-$store.cer") -Force | Out-Null
            Import-Certificate -FilePath (Join-Path $env:TEMP "pacc-test-$store.cer") -CertStoreLocation $path | Out-Null
            Write-Host ("已导入受信任存储：{0}" -f $path) -ForegroundColor Green
        }
        Write-Host "提示：加载测试签名驱动前请在目标机执行  bcdedit /set testsigning on  并重启。" -ForegroundColor Yellow
    }
}

Write-Host ("`n请记录以下值，供 signtool / scripts/sign-artifacts.sh --test 使用：") -ForegroundColor Cyan
Write-Host ("  PACC_WIN_TEST_CERT_PFX={0}" -f (Resolve-Path $OutPfx))
Write-Host "  PACC_WIN_TEST_CERT_PASSWORD=<你设置的密码>"
Write-Host "  PACC_WIN_CERT_SUBJECT=$Subject"
