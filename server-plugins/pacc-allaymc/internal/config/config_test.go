package config

import (
	"testing"

	"github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/model"
)

const sampleYAML = `
# PACC 配置示例
server-id: "bedrock-1"
punishment-mode: 2
confidence-threshold: 75   # 行内注释
max-reach-distance: 3.5
max-cps: 20
max-horizontal-speed-per-tick: 0.5
max-place-per-tick: 2
fly-airborne-ticks-trigger: 30
enabled-checks:
  - MOVE
  - COMBAT
ptv:
  url: "https://ptv.example.com/"
  secret: "s3cr3t"
  server-id: "node-a"
`

func TestDefaultsAreAlertOnly(t *testing.T) {
	c := Defaults()
	if c.PunishmentMode != 0 {
		t.Fatalf("默认处置级别应为 0，实际 %d", c.PunishmentMode)
	}
	if c.MaxPunishment() != model.PunishmentAlert {
		t.Fatalf("默认最高处置应为 ALERT，实际 %s", c.MaxPunishment())
	}
	if c.MaxReachDistance != 3.1 || c.MaxCPS != 16 || c.MaxHorizontalSpeedPerTick != 0.42 {
		t.Fatalf("默认阈值不符：reach=%v cps=%d speed=%v", c.MaxReachDistance, c.MaxCPS, c.MaxHorizontalSpeedPerTick)
	}
}

func TestLoadParsesFields(t *testing.T) {
	c, err := Load([]byte(sampleYAML))
	if err != nil {
		t.Fatalf("Load 出错: %v", err)
	}
	if c.ServerID != "bedrock-1" {
		t.Errorf("server-id = %q", c.ServerID)
	}
	if c.PunishmentMode != 2 {
		t.Errorf("punishment-mode = %d", c.PunishmentMode)
	}
	if c.ConfidenceThreshold != 75 {
		t.Errorf("confidence-threshold = %d（行内注释应被剥离）", c.ConfidenceThreshold)
	}
	if c.MaxReachDistance != 3.5 || c.MaxCPS != 20 || c.MaxPlacePerTick != 2 {
		t.Errorf("阈值解析错误: %+v", c)
	}
	if c.FlyAirborneTicksTrigger != 30 {
		t.Errorf("fly-airborne-ticks-trigger = %d", c.FlyAirborneTicksTrigger)
	}
	if len(c.EnabledChecks) != 2 || c.EnabledChecks[0] != "MOVE" || c.EnabledChecks[1] != "COMBAT" {
		t.Errorf("enabled-checks = %v", c.EnabledChecks)
	}
	if c.PTV.URL != "https://ptv.example.com/" || c.PTV.Secret != "s3cr3t" || c.PTV.ServerID != "node-a" {
		t.Errorf("ptv 解析错误: %+v", c.PTV)
	}
}

func TestLoadMissingKeysUseDefaults(t *testing.T) {
	c, err := Load([]byte("punishment-mode: 1\n"))
	if err != nil {
		t.Fatalf("Load 出错: %v", err)
	}
	if c.PunishmentMode != 1 {
		t.Errorf("punishment-mode = %d", c.PunishmentMode)
	}
	// 未出现的键必须沿用默认值。
	if c.MaxReachDistance != 3.1 || c.MaxCPS != 16 {
		t.Errorf("缺失键未回落到默认值: %+v", c)
	}
}

func TestLoadEmptyUsesDefaults(t *testing.T) {
	c, err := Load(nil)
	if err != nil {
		t.Fatalf("Load 出错: %v", err)
	}
	if c.PunishmentMode != 0 || c.MaxCPS != 16 {
		t.Errorf("空配置应返回默认值: %+v", c)
	}
}

func TestPunishmentClamp(t *testing.T) {
	cases := map[string]int{
		"punishment-mode: -1\n": 0,
		"punishment-mode: 3\n":  2,
		"punishment-mode: 1\n":  1,
	}
	for in, want := range cases {
		c, err := Load([]byte(in))
		if err != nil {
			t.Fatalf("Load(%q) 出错: %v", in, err)
		}
		if c.PunishmentMode != want {
			t.Errorf("Load(%q) punishment-mode = %d，期望 %d", in, c.PunishmentMode, want)
		}
	}
}

func TestMaxPunishmentMapping(t *testing.T) {
	cases := []struct {
		mode int
		want model.PunishmentType
	}{
		{0, model.PunishmentAlert},
		{1, model.PunishmentKick},
		{2, model.PunishmentBan},
	}
	for _, tc := range cases {
		c := Defaults()
		c.PunishmentMode = tc.mode
		if got := c.MaxPunishment(); got != tc.want {
			t.Errorf("mode=%d -> %s，期望 %s", tc.mode, got, tc.want)
		}
	}
}

func TestIsEnabled(t *testing.T) {
	c, _ := Load([]byte(sampleYAML))
	if !c.IsEnabled("MOVE") || !c.IsEnabled("move") {
		t.Error("MOVE 应启用且大小写不敏感")
	}
	if c.IsEnabled("PACKET") {
		t.Error("PACKET 未在 enabled-checks 中，应停用")
	}
}

func TestEffectiveServerID(t *testing.T) {
	c, _ := Load([]byte(sampleYAML))
	if c.EffectiveServerID() != "node-a" {
		t.Errorf("应优先 ptv.server-id，实际 %q", c.EffectiveServerID())
	}
	c2, _ := Load([]byte("server-id: top\n"))
	if c2.EffectiveServerID() != "top" {
		t.Errorf("ptv.server-id 空时应回退顶层，实际 %q", c2.EffectiveServerID())
	}
}
