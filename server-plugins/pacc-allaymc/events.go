// 事件源抽象：插件不直接依赖 AllayMC SDK，宿主实现本接口并在 OnEnable 之前注入。
package main

import "github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/model"

// EventSource 抽象 AllayMC 的事件订阅能力。
//
// 与 ServerConn 一样，这是插件与平台之间的接缝：AllayMC 各版本的事件 API 不同，宿主编排本接口即可，
// 检测与上报逻辑不受影响。宿主在调用 OnEnable 之前通过 AttachEventSource 注入；未注入时 OnEnable
// 会直接报错，避免出现「插件已启用但从不检测」的静默失效。
type EventSource interface {
	// OnPlayerMove 订阅玩家移动快照。
	OnPlayerMove(handler func(model.PlayerPosition))
	// OnPlayerAttack 订阅玩家攻击事件。
	OnPlayerAttack(handler func(model.CombatEvent))
	// OnPlayerQuit 订阅玩家退出，用于重置该玩家的检测状态。
	OnPlayerQuit(handler func(playerID string))
}
