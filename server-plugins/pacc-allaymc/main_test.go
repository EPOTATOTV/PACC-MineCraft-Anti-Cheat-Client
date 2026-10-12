package main

import (
	"testing"

	"github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/config"
	"github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/model"
)

// stubServer 是 ServerConn 的最小桩，用于在无 AllayMC 服务端的情况下跑通装配。
type stubServer struct{}

func (stubServer) ServerName() string              { return "test" }
func (stubServer) ServerVersion() string           { return "1.21" }
func (stubServer) MaxPlayers() int                 { return 10 }
func (stubServer) PlayerName(string) string        { return "tester" }
func (stubServer) Message(string, string)          {}
func (stubServer) Disconnect(string, string) error { return nil }
func (stubServer) BanPlayer(string, string) error  { return nil }

// captureReporter 记录上报的检测事件，替代真实 HTTP 通道。
type captureReporter struct {
	events []model.DetectionEvent
}

func (c *captureReporter) Register(model.ServerInfo) {}

func (c *captureReporter) Report(ev model.DetectionEvent) { c.events = append(c.events, ev) }

func (c *captureReporter) ReportPunishment(string, string, model.PunishmentType, string, int64) {}

// stubEvents 记录被登记的回调，允许测试直接触发它们模拟平台事件。
type stubEvents struct {
	move   func(model.PlayerPosition)
	attack func(model.CombatEvent)
	quit   func(string)
}

func (s *stubEvents) OnPlayerMove(h func(model.PlayerPosition)) { s.move = h }

func (s *stubEvents) OnPlayerAttack(h func(model.CombatEvent)) { s.attack = h }

func (s *stubEvents) OnPlayerQuit(h func(string)) { s.quit = h }

func TestOnEnableRequiresEventSource(t *testing.T) {
	p := New(config.Defaults(), stubServer{})
	if err := p.OnEnable(); err == nil {
		t.Fatal("未注入事件源时 OnEnable 应报错，而不是静默启用")
	}
}

func TestOnEnableRegistersHandlersAndDetects(t *testing.T) {
	rep := &captureReporter{}
	p := New(config.Defaults(), stubServer{})
	p.reporter = rep

	events := &stubEvents{}
	p.AttachEventSource(events)
	if err := p.OnEnable(); err != nil {
		t.Fatalf("OnEnable 失败: %v", err)
	}
	if events.move == nil || events.attack == nil || events.quit == nil {
		t.Fatal("OnEnable 应登记移动 / 战斗 / 退出三类回调")
	}

	// 经由事件源喂入两条移动：第二条水平位移 5 格，远超默认阈值 0.42，应触发 SPEED。
	events.move(model.PlayerPosition{
		PlayerID: "p1", Pos: model.Position{X: 0, Y: 64, Z: 0}, TimestampMs: 0, OnGround: true,
	})
	events.move(model.PlayerPosition{
		PlayerID: "p1", Pos: model.Position{X: 5, Y: 64, Z: 0}, TimestampMs: 50, OnGround: true,
	})

	if len(rep.events) == 0 {
		t.Fatal("事件源喂入的超速移动应产生上报")
	}
	if rep.events[0].CheatType != "SPEED" {
		t.Fatalf("应命中 SPEED，实际 %s", rep.events[0].CheatType)
	}

	// 退出回调应重置该玩家状态：重置后首帧无历史，不再触发。
	events.quit("p1")
	events.move(model.PlayerPosition{
		PlayerID: "p1", Pos: model.Position{X: 50, Y: 64, Z: 0}, TimestampMs: 100, OnGround: true,
	})
	if len(rep.events) != 1 {
		t.Fatalf("退出重置后首帧不应再触发，事件数=%d", len(rep.events))
	}
}
