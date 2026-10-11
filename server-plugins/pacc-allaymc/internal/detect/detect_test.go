package detect

import (
	"math"
	"testing"

	"github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/config"
	"github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/model"
)

func approx(a, b, eps float64) bool {
	return math.Abs(a-b) <= eps
}

func move(id string, x, y, z float64, onGround bool, ts int64) model.PlayerPosition {
	return model.PlayerPosition{
		PlayerID:    id,
		Pos:         model.Position{X: x, Y: y, Z: z},
		TimestampMs: ts,
		OnGround:    onGround,
	}
}

func hasCheat(vs []model.Violation, cheat string) bool {
	for _, v := range vs {
		if v.CheatType == cheat {
			return true
		}
	}
	return false
}

func TestHorizontalDistance(t *testing.T) {
	d := HorizontalDistance(model.Position{}, model.Position{X: 3, Y: 100, Z: 4})
	if !approx(d, 5, 1e-9) {
		t.Fatalf("水平距离应为 5（忽略 Y），实际 %v", d)
	}
}

func TestViewAngleDeg(t *testing.T) {
	// yaw=0、pitch=0 时正面朝向 +Z，指向 +Z 的目标夹角应为 0。
	if a := ViewAngleDeg(0, 0, 0, 0, 1); !approx(a, 0, 1e-6) {
		t.Errorf("正对目标夹角应为 0，实际 %v", a)
	}
	// 指向 -Z（背后）夹角应为 180。
	if a := ViewAngleDeg(0, 0, 0, 0, -1); !approx(a, 180, 1e-6) {
		t.Errorf("背对目标夹角应为 180，实际 %v", a)
	}
	// 零向量退化返回 0，不产生 NaN。
	if a := ViewAngleDeg(0, 0, 0, 0, 0); a != 0 {
		t.Errorf("零向量应返回 0，实际 %v", a)
	}
}

func TestSpeedViolation(t *testing.T) {
	e := NewEngine(config.Defaults())
	e.HandleMove(move("p1", 0, 64, 0, true, 0))
	vs := e.HandleMove(move("p1", 5, 64, 0, true, 50))
	if !hasCheat(vs, CheatSpeed) {
		t.Fatalf("水平位移 5 格应触发 SPEED，实际 %+v", vs)
	}
	if vs[0].Confidence != 100 {
		t.Errorf("远超阈值置信度应为 100，实际 %d", vs[0].Confidence)
	}
}

func TestNoViolationWithinThreshold(t *testing.T) {
	e := NewEngine(config.Defaults())
	e.HandleMove(move("p1", 0, 64, 0, true, 0))
	vs := e.HandleMove(move("p1", 0.3, 64, 0, true, 50))
	if len(vs) != 0 {
		t.Fatalf("阈值内不应有违规，实际 %+v", vs)
	}
}

func TestFlyViolation(t *testing.T) {
	e := NewEngine(config.Defaults())
	// 默认 fly-airborne-ticks-trigger=20，持续滞空上升。
	e.HandleMove(move("p1", 0, 64, 0, false, 0))
	var last []model.Violation
	for i := 1; i <= 25; i++ {
		last = e.HandleMove(move("p1", 0, 64+0.1*float64(i), 0, false, int64(i*50)))
	}
	if !hasCheat(last, CheatFly) {
		t.Fatalf("持续滞空上升应触发 FLY，实际 %+v", last)
	}
}

func TestFlyResetsOnGround(t *testing.T) {
	e := NewEngine(config.Defaults())
	e.HandleMove(move("p1", 0, 64, 0, false, 0))
	for i := 1; i <= 25; i++ {
		e.HandleMove(move("p1", 0, 64+0.1*float64(i), 0, false, int64(i*50)))
	}
	// 落地后状态清零，再滞空不足触发刻数不应命中。
	vs := e.HandleMove(move("p1", 0, 66, 0, true, 2000))
	if hasCheat(vs, CheatFly) {
		t.Fatalf("落地应重置滞空计数，实际 %+v", vs)
	}
}

func TestReachViolation(t *testing.T) {
	e := NewEngine(config.Defaults())
	ev := model.CombatEvent{
		AttackerID:  "p1",
		TargetID:    "p2",
		AttackerPos: model.Position{},
		TargetPos:   model.Position{Z: 10},
		TimestampMs: 1000,
	}
	vs := e.HandleAttack(ev)
	if !hasCheat(vs, CheatReach) {
		t.Fatalf("攻击距离 10 应触发 REACH，实际 %+v", vs)
	}
}

func TestCPSViolation(t *testing.T) {
	e := NewEngine(config.Defaults())
	var last []model.Violation
	// 默认 max-cps=16，同一秒内第 17 次攻击命中。
	for i := 0; i < 20; i++ {
		last = e.HandleAttack(model.CombatEvent{
			AttackerID:  "p1",
			TargetID:    "p2",
			AttackerPos: model.Position{},
			TargetPos:   model.Position{Z: 1},
			TimestampMs: int64(1000 + i),
		})
	}
	if !hasCheat(last, CheatAutoClick) {
		t.Fatalf("高 CPS 应触发 AUTO_CLICKER，实际 %+v", last)
	}
}

func TestResetClearsState(t *testing.T) {
	e := NewEngine(config.Defaults())
	e.HandleMove(move("p1", 0, 64, 0, true, 0))
	e.Reset("p1")
	// 重置后第一帧无历史，不应报 SPEED。
	vs := e.HandleMove(move("p1", 50, 64, 0, true, 50))
	if len(vs) != 0 {
		t.Fatalf("重置后不应有违规，实际 %+v", vs)
	}
}

func TestPruneAttackTimesWindow(t *testing.T) {
	kept := pruneAttackTimes([]int64{0, 500, 1500, 2000}, 2000)
	for _, ts := range kept {
		if 2000-ts > cpsWindowMs {
			t.Fatalf("窗口外的 %d 应被裁剪，结果 %v", ts, kept)
		}
	}
	if len(kept) != 2 {
		t.Fatalf("期望保留最近 1 秒内 2 条，实际 %v", kept)
	}
}
