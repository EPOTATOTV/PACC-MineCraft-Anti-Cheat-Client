; ============================================================
; PACC Windows 客户端安装向导（Inno Setup 6）
; 依赖：Inno Setup 6（https://jrsoftware.org/isinfo.php）
; 用法：ISCC.exe pacc-client-installer.iss
; 产出：Output\PACCClientSetup-{#MyAppVersion}.exe
;
; 安装源目录 {#DistDir}（仓库根 dist\win-x64）由 release.yml 的 build-windows job 汇聚：
;   PACCClient.exe            Tauri 桌面壳（主客户端）
;   bin\ptv-client-*.jar      Java 探针
;   bin\pacc-client.properties 配置模板（运行时配置另由 installer.ps1 落 DataDir）
;   manager\*                 C# WPF 诊断工具（PaccManager.exe 及依赖）
;   drivers\*                 内核驱动（可选；本仓库暂无预构建制品）
;
; 产物缺失：每个 [Files] 条目都带 skipifsourcedoesntexist，缺某个产物只跳过、不致编译失败。
; （另一种写法是 #if FileExists('...') 预处理，需要每个条目各写一遍；这里统一用标志更简洁。）
; ============================================================

#define MyAppName "PACC 客户端"
#define MyAppVersion "5.4.0"
#define MyAppPublisher "PotatoTV"
; 主客户端是 Tauri 桌面壳；诊断工具在 manager\ 下
#define MyAppExeName "PACCClient.exe"
#define DistDir "..\..\dist\win-x64"

[Setup]
AppId={{8F3C1E52-7C24-4B6E-9A0D-4E5B2C6D8F01}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppVerName={#MyAppName} {#MyAppVersion}
; 与 installer.ps1 的 -InstallDir 默认值一致
DefaultDirName={autopf}\PACC
DefaultGroupName={#MyAppName}
; 强制协议确认：勾选框形式（用户须勾选同意才能继续）
LicenseFile=license.rtf
LicenseForceSelection=checkbox
; 安装到 Program Files 需要管理员权限（仅安装器提权；客户端运行时不强制 UAC）
PrivilegesRequired=admin
OutputDir=Output
OutputBaseFilename=PACCClientSetup-{#MyAppVersion}
; 如需安装向导图标：放一个 installer.ico 到本目录并去掉下面 SetupIconFile 的注释
; SetupIconFile=installer.ico
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
MinVersion=10.0.17763
UninstallDisplayIcon={app}\{#MyAppExeName}

; ---------- 文件 ----------
[Files]
; Tauri 桌面壳（主客户端）
Source: "{#DistDir}\PACCClient.exe"; DestDir: "{app}"; Flags: ignoreversion

; Java 探针（运行时需要，可单独更新；按版本通配，避免写死版本号漂移）
Source: "{#DistDir}\bin\ptv-client-*.jar"; DestDir: "{app}\bin"; Flags: ignoreversion skipifsourcedoesntexist

; 配置模板（运行时配置由 installer.ps1 生成到 {commonappdata}\PACC；这里保留安装目录内基线）
Source: "{#DistDir}\bin\pacc-client.properties"; DestDir: "{app}\bin"; Flags: ignoreversion skipifsourcedoesntexist

; C# WPF 诊断工具（PaccManager.exe 及全部依赖），整目录拷到 manager\
Source: "{#DistDir}\manager\*"; DestDir: "{app}\manager"; Flags: ignoreversion recursesubdirs createallsubdirs skipifsourcedoesntexist

; 内核驱动（PaccPtv.sys / .cat / .inf）：可选组件，仓库无预构建制品时跳过
Source: "{#DistDir}\drivers\*"; DestDir: "{app}\drivers"; Flags: ignoreversion skipifsourcedoesntexist

; 安装编排脚本（[Run]/[UninstallRun] 及手动修复调用）
Source: "..\windows-gui\deploy\installer.ps1"; DestDir: "{app}\deploy"; Flags: ignoreversion

; 玩家协议（安装向导展示；文本需由法务替换，见文件头说明）
Source: "license.rtf"; DestDir: "{app}"; Flags: ignoreversion

; ---------- 快捷方式 ----------
[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
; 诊断工具快捷方式（manager\ 目录由 [Files] 整目录安装；[Icons] 不做存在性校验，无需条件标志）
Name: "{group}\PACC 诊断工具"; Filename: "{app}\manager\PaccManager.exe"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

; ---------- 任务选项 ----------
[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加图标：";

; ---------- 注册表：写卸载信息 & 版本 ----------
[Registry]
Root: HKCU; Subkey: "Software\{#MyAppPublisher}\{#MyAppName}"; ValueType: string; ValueName: "InstallPath"; ValueData: "{app}"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\{#MyAppPublisher}\{#MyAppName}"; ValueType: string; ValueName: "Version"; ValueData: "{#MyAppVersion}"; Flags: uninsdeletevalue

; ---------- 安装后：调用安装编排脚本；完成页勾选启动 ----------
[Run]
; 安装后执行安装编排：环境预检、配置基线、驱动接线、开机自启
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; \
  Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\deploy\installer.ps1"" -InstallDir ""{app}"""; \
  StatusMsg: "正在完成 PACC 组件安装（配置 / 驱动 / 自启）..."; \
  Flags: runhidden waituntilterminated

; 安装完成勾选启动；--first-run 触发首次初始化
Filename: "{app}\{#MyAppExeName}"; Description: "启动 {#MyAppName}"; \
  Parameters: "--first-run"; Flags: nowait postinstall skipifsilent

; ---------- 卸载后：调用卸载编排（数据目录保留） ----------
[UninstallRun]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; \
  Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\deploy\installer.ps1"" -Uninstall"; \
  Flags: runhidden waituntilterminated; RunOnceId: "PaccUninstall"

; ---------- 向导代码：环境预检 + 旧版本提示 ----------
[Code]
const
  { Windows 10 1809 的内部版本号 }
  PACC_MIN_BUILD  = 17763;
  PACC_MIN_FREE_MB = 500;

{ 从安装目录取出盘符（"C:\..." -> "C"） }
function GetInstallDrive(const Dir: String): String;
begin
  if Length(Dir) >= 2 then
    Result := Copy(Dir, 1, 1)
  else
    Result := 'C';
end;

function CheckEnvironment(): Boolean;
var
  WV: TWindowsVersion;
  FreeBytes, TotalBytes: Int64;
begin
  Result := True;

  { Win10 与 Win11 都报告 10.0，只能靠 build 号区分 }
  GetWindowsVersionEx(WV);
  if (WV.Major < 10) or ((WV.Major = 10) and (WV.BuildNumber < PACC_MIN_BUILD)) then
  begin
    MsgBox('PACC 需要 Windows 10 1809（内部版本 17763）或更高版本。' + #13#10 +
           '当前系统：' + IntToStr(WV.Major) + '.' + IntToStr(WV.Minor) +
           '（build ' + IntToStr(WV.BuildNumber) + '）。', mbCriticalError, MB_OK);
    Result := False;
  end;

  if (ProcessorArchitecture <> paX64) and (ProcessorArchitecture <> paArm64) then
  begin
    MsgBox('PACC 仅支持 64 位 Windows（x64 / ARM64）。', mbCriticalError, MB_OK);
    Result := False;
  end;

  if GetSpaceOnDisk64(GetInstallDrive(WizardDirValue), FreeBytes, TotalBytes) then
  begin
    if FreeBytes < (Int64(PACC_MIN_FREE_MB) * 1024 * 1024) then
    begin
      MsgBox('安装分区可用空间不足，至少需要 500 MB。', mbCriticalError, MB_OK);
      Result := False;
    end;
  end;
end;

function IsOldVersionInstalled(): Boolean;
begin
  { Inno 的 AppId 在注册表里存为去掉转义花括号后的字面 GUID }
  Result := RegKeyExists(HKLM,
    'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{8F3C1E52-7C24-4B6E-9A0D-4E5B2C6D8F01}')
    or FileExists(AddBackslash(WizardDirValue) + '{#MyAppExeName}');
end;

{ 环境预检放在 InitializeSetup：只有它的返回值能中止向导，
  InitializeWizard 已经进入向导流程，无法从那里阻止继续。 }
function InitializeSetup(): Boolean;
begin
  Result := CheckEnvironment();
end;

procedure InitializeWizard;
begin
  WizardForm.FilenameLabel.Caption := '{#MyAppName} {#MyAppVersion} 安装向导';
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = wpSelectDir then
  begin
    if IsOldVersionInstalled() then
    begin
      if MsgBox('检测到已安装的 PACC 版本，继续将覆盖升级，是否继续？',
                mbConfirmation, MB_YESNO) = IDNO then
        Result := False;
    end;
  end;
end;