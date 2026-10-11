// PACC AllayMC 插件入口（基岩版，纯 Go 实现，v6.0.0）。
//
// 本文件是插件生命周期与装配的骨架：把配置、检测引擎、PTV 上报通道和 AllayMC 平台能力接在一起。
// 平台相关的具体调用（踢出 / 封禁 / 消息）通过 ServerConn 接口隔离，AllayMC 的实际接口签名若不同，
// 只需实现本接口而不必改动检测与上报逻辑。
//
// 硬约束：本插件只做本服务器内的本地检测与留痕；处置默认仅告警（punishment-mode=0），
// 踢出 / 封禁必须由运维显式打开且只作用于本服务器，不跨服务器，不对接任何第三方封禁系统。
package main

import (
	"fmt"
	"log"
	"os"

	"github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/config"
	"github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/detect"
	"github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/model"
	"github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/report"
)

// pluginVersion 插件版本，与 plugin.yaml、仓库版本基准保持一致。
const pluginVersion = "6.0.0"

// ServerConn 抽象 PACC 需要的 AllayMC 服务端能力。只暴露最小集合，便于替换成桩实现做联调。
//
// 注意：以下方法名对应 AllayMC 的 player.Disconnect() 与 server.BanPlayer()，
// 但 AllayMC 不同版本的接口签名可能不同，本骨架未绑定具体 AllayMC SDK。
// 接入时按实际版本校准这些方法，并在此封装内完成类型转换与线程调度。
type ServerConn interface {
	// ServerName 服务端名称。
	ServerName() string
	// ServerVersion 服务端版本号。
	ServerVersion() string
	// MaxPlayers 最大玩家数。
	MaxPlayers() int
	// PlayerName 取玩家显示名（未知返回空串）。
	PlayerName(playerID string) string
	// Message 给单个玩家发提示（本服务器内）。
	Message(playerID, message string)
	// Disconnect 踢出玩家（仅本服务器内）。
	Disconnect(playerID, reason string) error
	// BanPlayer 在本服务器内封禁玩家，不跨服、不对接第三方封禁系统。
	BanPlayer(playerID, reason string) error
}

// Plugin PACC AllayMC 插件实例。AllayMC 加载后依次调用 Init、OnEnable，卸载时调用 OnDisable。
type Plugin struct {
	cfg      config.Config
	engine   *detect.Engine
	reporter report.Reporter
	server   ServerConn
	logger   *log.Logger
}

// New 构造插件实例。config 与 ServerConn 由 AllayMC 侧在加载时提供。
func New(cfg config.Config, server ServerConn) *Plugin {
	var reporter report.Reporter = report.Noop{}
	if cfg.PTV.URL != "" {
		reporter = report.NewHTTP(cfg.PTV.URL, cfg.PTV.Secret)
	}
	return &Plugin{
		cfg:      cfg,
		engine:   detect.NewEngine(cfg),
		reporter: reporter,
		server:   server,
		logger:   log.New(os.Stdout, "[PACC] ", log.LstdFlags),
	}
}

// Init 插件初始化：上报服务端注册信息。
func (p *Plugin) Init() error {
	p.reporter.Register(model.ServerInfo{
		ServerID:      p.cfg.EffectiveServerID(),
		ServerType:    model.ServerTypeAllayMC,
		PluginVersion: pluginVersion,
		ServerVersion: p.server.ServerVersion(),
		ServerName:    p.server.ServerName(),
		MaxPlayers:    p.server.MaxPlayers(),
	})
	p.logger.Printf("初始化完成：server-id=%q 处置级别=%d", p.cfg.EffectiveServerID(), p.cfg.PunishmentMode)
	return nil
}

// OnEnable 插件启用：此处登记平台事件监听（需按 AllayMC 实际事件 API 校准）。
func (p *Plugin) OnEnable() error {
	// TODO(AllayMC): 在此注册移动 / 战斗事件监听，把事件转成量之后调用
	//   p.OnPlayerMove(...) 与 p.OnPlayerAttack(...)。
	p.logger.Printf("PACC 服务端检测已启用（ALLAYMC，处置级别 %d）", p.cfg.PunishmentMode)
	return nil
}

// OnDisable 插件卸载：当前仅打印日志，无需释放长期资源。
func (p *Plugin) OnDisable() error {
	p.logger.Printf("PACC 服务端检测已卸载")
	return nil
}

// OnPlayerMove 由 AllayMC 移动事件回调调用。
func (p *Plugin) OnPlayerMove(pos model.PlayerPosition) {
	for _, v := range p.engine.HandleMove(pos) {
		p.handleViolation(v, pos.PlayerID)
	}
}

// OnPlayerAttack 由 AllayMC 战斗事件回调调用。
func (p *Plugin) OnPlayerAttack(ev model.CombatEvent) {
	for _, v := range p.engine.HandleAttack(ev) {
		p.handleViolation(v, ev.AttackerID)
	}
}

// OnPlayerLeave 玩家离开时重置检测状态。
func (p *Plugin) OnPlayerLeave(playerID string) {
	p.engine.Reset(playerID)
}

// handleViolation 处理单条命中：一律留痕上报；达到置信度阈值才告警，并按处置级别执行本服务器内的处置。
func (p *Plugin) handleViolation(v model.Violation, playerID string) {
	serverID := p.cfg.EffectiveServerID()

	// 留痕：无论是否告警都上报，低置信度事件也保留证据。
	p.reporter.Report(model.DetectionEvent{
		ServerID:    serverID,
		PlayerUUID:  playerID,
		PlayerName:  p.server.PlayerName(playerID),
		CheckType:   string(v.CheckType),
		CheatType:   v.CheatType,
		Confidence:  v.Confidence,
		Detail:      v.Detail,
		TimestampMs: v.TimestampMs,
	})

	if v.Confidence < p.cfg.ConfidenceThreshold {
		return
	}

	reason := fmt.Sprintf("[PACC] 检测命中：%s（置信度 %d）", v.CheatType, v.Confidence)
	p.server.Message(playerID, reason)

	switch p.cfg.MaxPunishment() {
	case model.PunishmentKick:
		if err := p.server.Disconnect(playerID, reason); err != nil {
			p.logger.Printf("踢出失败：%s %v", playerID, err)
			return
		}
		p.reporter.ReportPunishment(serverID, playerID, model.PunishmentKick, v.CheatType, 0)
	case model.PunishmentBan:
		if err := p.server.BanPlayer(playerID, reason); err != nil {
			p.logger.Printf("封禁失败：%s %v", playerID, err)
			return
		}
		p.reporter.ReportPunishment(serverID, playerID, model.PunishmentBan, v.CheatType, 0)
	default:
		// 仅告警：不做任何处置。
	}
}

// LoadConfig 从磁盘读取并解析配置。文件不存在时返回默认配置，便于首次启动。
func LoadConfig(path string) (config.Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return config.Defaults(), nil
		}
		return config.Config{}, err
	}
	return config.Load(data)
}

// main 仅用于让本包能作为普通可执行程序编译（go build ./...）。
// AllayMC 以 Go 插件（go build -buildmode=plugin）或内嵌方式加载时，
// 入口是导出的 New / Init / OnEnable，不会执行到这里。
func main() {
	log.Println("[PACC] 这是 AllayMC 插件构建产物；请由 AllayMC 依据 plugin.yaml 登记后加载。")
}
