// Package detect 实现与平台无关的基础检测逻辑：移动（速度 / 滞空）与战斗（触及距离 / CPS）。
//
// 全部为纯计算 + 内存状态，不触碰网络与平台 API，因此可在没有 AllayMC 服务端的情况下单测。
// 阈值采用保守默认值，具体见 config.Config；判定只产出 Violation，是否告警 / 处置由调用方决定。
package detect

import (
	"math"
	"sync"

	"github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/config"
	"github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/model"
)

// 作弊类型常量，与上报的 cheatType 字段对齐。
const (
	CheatSpeed     = "SPEED"
	CheatFly       = "FLY"
	CheatReach     = "REACH"
	CheatAutoClick = "AUTO_CLICKER"
)

const (
	cpsWindowMs    = 1000
	maxAttackTimes = 32
)

// HorizontalDistance 计算两点在水平面（XZ）上的距离。
func HorizontalDistance(a, b model.Position) float64 {
	return math.Hypot(b.X-a.X, b.Z-a.Z)
}

// ViewAngleDeg 计算攻击者朝向与指向目标方向之间的夹角（度，范围 [0,180]，0 表示正对目标）。
// 约定与 Java 内核一致：yaw 0=+Z、90=-X，pitch 正值朝下。
func ViewAngleDeg(yawDeg, pitchDeg, dx, dy, dz float64) float64 {
	length := math.Sqrt(dx*dx + dy*dy + dz*dz)
	if length < 1e-9 {
		return 0
	}
	yaw := yawDeg * math.Pi / 180
	pitch := pitchDeg * math.Pi / 180
	fx := -math.Sin(yaw) * math.Cos(pitch)
	fy := -math.Sin(pitch)
	fz := math.Cos(yaw) * math.Cos(pitch)
	dot := fx*(dx/length) + fy*(dy/length) + fz*(dz/length)
	dot = math.Max(-1, math.Min(1, dot))
	return math.Acos(dot) * 180 / math.Pi
}

// confidence 把"超出阈值的倍数"映射成 [0,100] 的置信度：1 倍封顶，2 倍满值。
func confidence(ratio float64) int {
	if ratio <= 1 {
		return 0
	}
	c := int((ratio - 1) * 100)
	if c > 100 {
		c = 100
	}
	if c < 0 {
		c = 0
	}
	return c
}

type playerState struct {
	lastPos       model.Position
	hasLast       bool
	airborneTicks int
	attackTimes   []int64
}

// Engine 有状态的检测聚合器，按玩家维护移动与点击历史。
type Engine struct {
	cfg    config.Config
	mu     sync.Mutex
	states map[string]*playerState
}

// NewEngine 构造检测引擎。
func NewEngine(cfg config.Config) *Engine {
	return &Engine{cfg: cfg, states: map[string]*playerState{}}
}

// Reset 清空某玩家的历史状态（玩家离开、传送后调用，避免跳变误判）。
func (e *Engine) Reset(playerID string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	delete(e.states, playerID)
}

func (e *Engine) stateLocked(playerID string) *playerState {
	st := e.states[playerID]
	if st == nil {
		st = &playerState{}
		e.states[playerID] = st
	}
	return st
}

// HandleMove 处理一次移动快照，返回命中的违规项。
func (e *Engine) HandleMove(p model.PlayerPosition) []model.Violation {
	e.mu.Lock()
	defer e.mu.Unlock()

	st := e.stateLocked(p.PlayerID)
	var out []model.Violation

	if st.hasLast {
		dist := HorizontalDistance(st.lastPos, p.Pos)
		climb := p.Pos.Y - st.lastPos.Y

		if e.cfg.IsEnabled(string(model.CheckMove)) && e.cfg.MaxHorizontalSpeedPerTick > 0 &&
			dist > e.cfg.MaxHorizontalSpeedPerTick {
			out = append(out, model.Violation{
				PlayerID:    p.PlayerID,
				CheckType:   model.CheckMove,
				CheatType:   CheatSpeed,
				Confidence:  confidence(dist / e.cfg.MaxHorizontalSpeedPerTick),
				Detail:      "水平位移超过阈值",
				TimestampMs: p.TimestampMs,
			})
		}

		// 连续滞空：空中累积计数，落地清零。
		if p.OnGround {
			st.airborneTicks = 0
		} else {
			st.airborneTicks++
		}
		if e.cfg.IsEnabled(string(model.CheckMove)) && e.cfg.FlyAirborneTicksTrigger > 0 &&
			!p.OnGround && climb > 0 && st.airborneTicks >= e.cfg.FlyAirborneTicksTrigger {
			out = append(out, model.Violation{
				PlayerID:    p.PlayerID,
				CheckType:   model.CheckMove,
				CheatType:   CheatFly,
				Confidence:  confidence(float64(st.airborneTicks) / float64(e.cfg.FlyAirborneTicksTrigger)),
				Detail:      "持续滞空上升",
				TimestampMs: p.TimestampMs,
			})
		}
	}

	st.lastPos = p.Pos
	st.hasLast = true
	return out
}

// HandleAttack 处理一次玩家攻击事件，返回命中的违规项（触及距离 / CPS）。
func (e *Engine) HandleAttack(ev model.CombatEvent) []model.Violation {
	e.mu.Lock()
	defer e.mu.Unlock()

	st := e.stateLocked(ev.AttackerID)
	var out []model.Violation

	if e.cfg.IsEnabled(string(model.CheckCombat)) {
		dist := ev.AttackerPos.DistanceTo(ev.TargetPos)
		if e.cfg.MaxReachDistance > 0 && dist > e.cfg.MaxReachDistance {
			out = append(out, model.Violation{
				PlayerID:    ev.AttackerID,
				CheckType:   model.CheckCombat,
				CheatType:   CheatReach,
				Confidence:  confidence(dist / e.cfg.MaxReachDistance),
				Detail:      "攻击距离超过阈值",
				TimestampMs: ev.TimestampMs,
			})
		}

		st.attackTimes = append(st.attackTimes, ev.TimestampMs)
		st.attackTimes = pruneAttackTimes(st.attackTimes, ev.TimestampMs)
		cps := len(st.attackTimes)
		if e.cfg.MaxCPS > 0 && cps > e.cfg.MaxCPS {
			out = append(out, model.Violation{
				PlayerID:    ev.AttackerID,
				CheckType:   model.CheckCombat,
				CheatType:   CheatAutoClick,
				Confidence:  confidence(float64(cps) / float64(e.cfg.MaxCPS)),
				Detail:      "每秒点击次数超过阈值",
				TimestampMs: ev.TimestampMs,
			})
		}
	}

	return out
}

// pruneAttackTimes 只保留最近 1 秒窗口内的点击时间戳，并限制长度上界。
func pruneAttackTimes(times []int64, now int64) []int64 {
	kept := times[:0]
	for _, t := range times {
		if now-t <= cpsWindowMs && t <= now {
			kept = append(kept, t)
		}
	}
	if len(kept) > maxAttackTimes {
		kept = kept[len(kept)-maxAttackTimes:]
	}
	return kept
}
