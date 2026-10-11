/* PACC 下载站 —— 多平台渠道目录（v6.0）
 *
 * 纯数据文件，不含逻辑，渲染交给 js/platform-center.js。
 * - artifactPattern 里的 {ver} 占位渠道版本（见 config.js 的 versionFor），
 *   {mc} 占位 Minecraft 版本，渲染时按渠道替换。
 * - name / updateMethod / installGuide 内嵌中英双语，按当前语言取值；
 *   界面通用文案（标签、按钮等）在 i18n/ 的文案表里，本文件只放渠道自身内容。
 * - sha256 缺省表示尚未发布，渲染为占位符；不要手填假校验和。
 */
window.PACC_PLATFORMS = [
  /* ---- 玩家端 ---- */
  {
    id: 'win',
    category: 'client',
    badge: 'WIN',
    name: { 'zh-CN': 'Windows', en: 'Windows' },
    artifactPattern: 'PACCClientSetup-{ver}.exe',
    platforms: ['Windows 10 / 11 x64'],
    updateMethod: {
      'zh-CN': '运行新版安装程序覆盖安装，驱动组件会一并更新。',
      en: 'Run the new installer over the old build; the driver updates with it.',
    },
    installGuide: {
      'zh-CN': ['以管理员身份运行安装程序。', '按提示安装驱动组件并重启系统。', '确认托盘图标常驻、防护状态正常。'],
      en: ['Run the installer as administrator.', 'Install the driver component when prompted, then reboot.', 'Check that the tray icon stays resident and protection shows as active.'],
    },
  },
  {
    id: 'linux',
    category: 'client',
    badge: 'LNX',
    name: { 'zh-CN': 'Linux', en: 'Linux' },
    artifactPattern: 'pacc-client-{ver}-linux-x86_64.tar.gz',
    platforms: ['.deb', '.rpm', 'AppImage'],
    updateMethod: {
      'zh-CN': '用发行版包管理器升级，或解压新包覆盖旧目录。',
      en: 'Upgrade with your package manager, or unpack the new archive over the old one.',
    },
    installGuide: {
      'zh-CN': ['按发行版选择 .deb / .rpm / AppImage。', '安装后启用 systemd 服务。', '确认守护进程随系统启动。'],
      en: ['Pick .deb, .rpm or the AppImage for your distro.', 'Enable the systemd service after install.', 'Confirm the daemon starts with the system.'],
    },
  },
  {
    id: 'android',
    category: 'client',
    badge: 'APK',
    name: { 'zh-CN': 'Android', en: 'Android' },
    artifactPattern: 'pacc-client-{ver}-android.apk',
    platforms: ['arm64-v8a', 'armeabi-v7a'],
    updateMethod: {
      'zh-CN': '在应用内检查更新，或安装新的 APK 覆盖。',
      en: 'Check for updates in-app, or install the new APK over the old one.',
    },
    installGuide: {
      'zh-CN': ['允许「安装未知来源」后安装 APK。', '授予后台常驻与自启权限。', '确认服务在通知栏常驻。'],
      en: ['Allow "install from unknown sources", then install the APK.', 'Grant background and autostart permissions.', 'Confirm the service stays resident in the notification bar.'],
    },
  },
  {
    id: 'ios',
    category: 'client',
    badge: 'IOS',
    name: { 'zh-CN': 'iOS / iPadOS', en: 'iOS / iPadOS' },
    artifactPattern: 'pacc-client-{ver}-ios.ipa',
    platforms: ['iOS 15+', 'iPadOS'],
    updateMethod: {
      'zh-CN': '通过 TestFlight 更新，或安装新的签名 IPA。',
      en: 'Update through TestFlight, or install the new signed IPA.',
    },
    installGuide: {
      'zh-CN': ['在「设置 → 通用 → VPN 与设备管理」信任证书。', '用 TestFlight 或签名工具安装。', '确认防护状态正常。'],
      en: ['Trust the certificate under Settings → General → VPN & Device Management.', 'Install via TestFlight or your signing tool.', 'Confirm protection shows as active.'],
    },
  },

  /* ---- 服务端插件 ---- */
  {
    id: 'paper',
    category: 'server',
    badge: 'PAPER',
    name: { 'zh-CN': 'Paper 服务端', en: 'Paper server' },
    artifactPattern: 'pacc-paper-{ver}.jar',
    platforms: ['Paper 1.20', 'Paper 1.21'],
    updateMethod: {
      'zh-CN': '替换 plugins 目录下的插件 jar，重启服务端。',
      en: 'Replace the plugin jar under plugins/, then restart the server.',
    },
    installGuide: {
      'zh-CN': ['把 jar 放进服务端的 plugins 目录。', '重启服务端载入插件。', '在控制台确认插件已启用。'],
      en: ['Drop the jar into the server plugins/ folder.', 'Restart the server to load it.', 'Confirm the plugin is enabled in the console.'],
    },
  },
  {
    id: 'spigot',
    category: 'server',
    badge: 'SPIGOT',
    name: { 'zh-CN': 'Spigot 服务端', en: 'Spigot server' },
    artifactPattern: 'pacc-spigot-{ver}.jar',
    platforms: ['Spigot 1.20', 'Spigot 1.21'],
    updateMethod: {
      'zh-CN': '替换 plugins 目录下的插件 jar，重启服务端。',
      en: 'Replace the plugin jar under plugins/, then restart the server.',
    },
    installGuide: {
      'zh-CN': ['把 jar 放进服务端的 plugins 目录。', '重启服务端载入插件。', '在控制台确认插件已启用。'],
      en: ['Drop the jar into the server plugins/ folder.', 'Restart the server to load it.', 'Confirm the plugin is enabled in the console.'],
    },
  },
  {
    id: 'nukkit',
    category: 'server',
    badge: 'NUKKIT',
    name: { 'zh-CN': 'Nukkit 服务端', en: 'Nukkit server' },
    artifactPattern: 'pacc-nukkit-{ver}.jar',
    platforms: ['Nukkit', 'Bedrock'],
    updateMethod: {
      'zh-CN': '替换 plugins 目录下的插件 jar，重启服务端。',
      en: 'Replace the plugin jar under plugins/, then restart the server.',
    },
    installGuide: {
      'zh-CN': ['把 jar 放进服务端的 plugins 目录。', '重启服务端载入插件。', '在控制台确认插件已启用。'],
      en: ['Drop the jar into the server plugins/ folder.', 'Restart the server to load it.', 'Confirm the plugin is enabled in the console.'],
    },
  },
  {
    id: 'nukkit-mot',
    category: 'server',
    badge: 'MOT',
    name: { 'zh-CN': 'Nukkit-MOT 服务端', en: 'Nukkit-MOT server' },
    artifactPattern: 'pacc-nukkit-mot-{ver}.jar',
    platforms: ['Nukkit-MOT', 'Bedrock'],
    updateMethod: {
      'zh-CN': '替换 plugins 目录下的插件 jar，重启服务端。',
      en: 'Replace the plugin jar under plugins/, then restart the server.',
    },
    installGuide: {
      'zh-CN': ['把 jar 放进服务端的 plugins 目录。', '重启服务端载入插件。', '在控制台确认插件已启用。'],
      en: ['Drop the jar into the server plugins/ folder.', 'Restart the server to load it.', 'Confirm the plugin is enabled in the console.'],
    },
  },
  {
    id: 'allaymc',
    category: 'server',
    badge: 'ALLAY',
    name: { 'zh-CN': 'AllayMC 服务端', en: 'AllayMC server' },
    artifactPattern: 'pacc-allaymc-{ver}-linux-amd64.tar.gz',
    platforms: ['AllayMC', 'linux-amd64'],
    updateMethod: {
      'zh-CN': '解压新包覆盖插件目录，重启服务端。',
      en: 'Unpack the new archive over the plugin folder, then restart the server.',
    },
    installGuide: {
      'zh-CN': ['解压到服务端的插件目录。', '按说明登记插件入口。', '重启服务端并确认加载成功。'],
      en: ['Unpack into the server plugins folder.', 'Register the plugin entry as documented.', 'Restart the server and confirm it loads.'],
    },
  },

  /* ---- 客户端模组 ---- */
  {
    id: 'forge',
    category: 'mod',
    badge: 'FORGE',
    name: { 'zh-CN': 'Forge 模组', en: 'Forge mod' },
    artifactPattern: 'pacc-forge-{ver}-mc{mc}.jar',
    platforms: ['Forge'],
    mcVersions: ['1.20', '1.21'],
    updateMethod: {
      'zh-CN': '用对应 Minecraft 版本的 jar 覆盖 mods 目录并重启。',
      en: 'Drop the jar for your Minecraft version into mods/ and restart.',
    },
    installGuide: {
      'zh-CN': ['按 Minecraft 版本选择对应 jar。', '放入 mods 目录，客户端与服务端都需安装。', '重启游戏或服务端确认加载。'],
      en: ['Pick the jar matching your Minecraft version.', 'Put it in mods/ — both client and server need it.', 'Restart the game or server and confirm it loads.'],
    },
  },
  {
    id: 'fabric',
    category: 'mod',
    badge: 'FABRIC',
    name: { 'zh-CN': 'Fabric 模组', en: 'Fabric mod' },
    artifactPattern: 'pacc-fabric-{ver}-mc{mc}.jar',
    platforms: ['Fabric'],
    mcVersions: ['1.20', '1.21'],
    updateMethod: {
      'zh-CN': '用对应 Minecraft 版本的 jar 覆盖 mods 目录并重启。',
      en: 'Drop the jar for your Minecraft version into mods/ and restart.',
    },
    installGuide: {
      'zh-CN': ['按 Minecraft 版本选择对应 jar。', '放入 mods 目录，客户端与服务端都需安装。', '重启游戏或服务端确认加载。'],
      en: ['Pick the jar matching your Minecraft version.', 'Put it in mods/ — both client and server need it.', 'Restart the game or server and confirm it loads.'],
    },
  },

  /* ---- 代理扩展 ---- */
  {
    id: 'geyser',
    category: 'extension',
    badge: 'GEYSER',
    name: { 'zh-CN': 'Geyser 扩展', en: 'Geyser extension' },
    artifactPattern: 'pacc-geyser-{ver}.jar',
    platforms: ['Geyser', 'Spigot / Paper'],
    updateMethod: {
      'zh-CN': '替换 extensions 目录下的扩展 jar，重启 Geyser。',
      en: 'Replace the extension jar under extensions/, then restart Geyser.',
    },
    installGuide: {
      'zh-CN': ['把 jar 放进 Geyser 的 extensions 目录。', '重启 Geyser 载入扩展。', '在日志中确认扩展已启用。'],
      en: ['Drop the jar into the Geyser extensions/ folder.', 'Restart Geyser to load it.', 'Confirm the extension is enabled in the log.'],
    },
  },

  /* ---- 配置中心 ---- */
  {
    id: 'apollo',
    category: 'config',
    badge: 'APOLLO',
    name: { 'zh-CN': 'Apollo 配置模板', en: 'Apollo config template' },
    artifactPattern: 'pacc-apollo-config-{ver}.zip',
    platforms: ['Apollo', 'config'],
    updateMethod: {
      'zh-CN': '解压模板覆盖配置目录，按需调整后再应用。',
      en: 'Unpack the template over your config folder, adjust, then apply.',
    },
    installGuide: {
      'zh-CN': ['解压得到配置模板。', '对照现有配置合入需要的字段。', '校验配置后重载服务。'],
      en: ['Unpack to get the config template.', 'Merge the fields you need into your config.', 'Validate and reload the service.'],
    },
  },
];
