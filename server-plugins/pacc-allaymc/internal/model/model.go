// Package model 定义不依赖平台的数据模型。与 pacc-server-core 的 Java 模型保持字段对齐，
// 便于同一份检测逻辑在基岩版（AllayMC）与 Java 版服务端上报相同格式的载荷。
package model

import "math"

// ServerTypeAllayMC 服务端平台类型，与 Java 内核 ServerType.ALLAYMC 对齐。
const ServerTypeAllayMC = "ALLAYMC"

// PunishmentType 处置类型。默认 PunishmentAlert 只告警；KICK 及以上必须由配置显式打开。
type PunishmentType string

const (
	PunishmentAlert   PunishmentType = "ALERT"
	PunishmentKick    PunishmentType = "KICK"
	PunishmentTempBan PunishmentType = "TEMP_BAN"
	PunishmentBan     PunishmentType = "BAN"
)

// CheckType 检查项分类，与上报的 checkType 字段一一对应。
type CheckType string

const (
	CheckMove            CheckType = "MOVE"
	CheckCombat          CheckType = "COMBAT"
	CheckPacket          CheckType = "PACKET"
	CheckInventory       CheckType = "INVENTORY"
	CheckInteraction     CheckType = "INTERACTION"
	CheckClientBrand     CheckType = "CLIENT_BRAND"
	CheckAddon           CheckType = "ADDON"
	CheckDataConsistency CheckType = "DATA_CONSISTENCY"
)

// Position 世界坐标（方块或实体坐标，单位格）。
type Position struct {
	X float64
	Y float64
	Z float64
}

// DistanceTo 两点直线距离。
func (p Position) DistanceTo(o Position) float64 {
	dx := p.X - o.X
	dy := p.Y - o.Y
	dz := p.Z - o.Z
	return math.Sqrt(dx*dx + dy*dy + dz*dz)
}

// PlayerPosition 玩家位置快照。
type PlayerPosition struct {
	PlayerID    string
	Pos         Position
	Yaw         float64
	Pitch       float64
	TimestampMs int64
	OnGround    bool
	GameMode    string
}

// CombatEvent 玩家攻击实体事件。视角夹角由适配层用 ViewAngleDeg 预先算好。
type CombatEvent struct {
	AttackerID   string
	TargetID     string
	AttackerPos  Position
	TargetPos    Position
	TimestampMs  int64
	Critical     bool
	ViewAngleDeg float64
}

// Violation 一条检测命中结果（尚未决定是否告警 / 处置）。
type Violation struct {
	PlayerID    string
	CheckType   CheckType
	CheatType   string
	Confidence  int
	Detail      string
	TimestampMs int64
}

// DetectionEvent 上报给 PTV 的检测事件。
type DetectionEvent struct {
	ServerID    string
	PlayerUUID  string
	PlayerName  string
	PTEID       string
	CheckType   string
	CheatType   string
	Confidence  int
	Detail      string
	TimestampMs int64
}

// ServerInfo 服务端注册信息。
type ServerInfo struct {
	ServerID      string
	ServerType    string
	PluginVersion string
	ServerVersion string
	ServerName    string
	MaxPlayers    int
}
