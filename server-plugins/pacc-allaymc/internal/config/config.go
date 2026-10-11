// Package config 负责加载 PACC AllayMC 插件配置（config.yml）。
//
// 为避免引入第三方 Yaml 依赖，这里实现一个覆盖本插件配置所需子集的最小 Yaml 解析器：
// 支持一层嵌套（如 ptv.url）、标量值、以及 "- item" 形式的列表。解析不做类型推断，
// 交给调用方按字段转成 int / float；缺失或解析失败的键一律回落到默认值。
package config

import (
	"strconv"
	"strings"

	"github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/model"
)

// PTV 上报通道配置。
type PTV struct {
	URL      string
	Secret   string
	ServerID string
}

// Config 服务端检测配置。处置级别由 PunishmentMode 控制：0=仅告警，1=踢出，2=封禁。
// 踢出与封禁都只作用于本服务器，必须由运维显式配置才生效。
type Config struct {
	ServerID                  string
	PunishmentMode            int
	ConfidenceThreshold       int
	MaxReachDistance          float64
	MaxCPS                    int
	MaxHorizontalSpeedPerTick float64
	MaxPlacePerTick           int
	FlyAirborneTicksTrigger   int
	EnabledChecks             []string
	PTV                       PTV
}

// Defaults 返回默认配置：全部检查开启，处置级别为仅告警（0）。
func Defaults() Config {
	return Config{
		ServerID:                  "",
		PunishmentMode:            0,
		ConfidenceThreshold:       60,
		MaxReachDistance:          3.1,
		MaxCPS:                    16,
		MaxHorizontalSpeedPerTick: 0.42,
		MaxPlacePerTick:           1,
		FlyAirborneTicksTrigger:   20,
		EnabledChecks:             []string{"MOVE", "COMBAT", "PACKET", "INVENTORY", "INTERACTION", "CLIENT_BRAND", "ADDON", "DATA_CONSISTENCY"},
		PTV:                       PTV{},
	}
}

// Load 解析 Yaml 内容并叠加到默认配置之上。data 为空时直接返回默认值。
func Load(data []byte) (Config, error) {
	c := Defaults()
	if len(data) == 0 {
		return c, nil
	}
	scalars, lists := parseYAML(string(data))

	if v, ok := scalars["server-id"]; ok {
		c.ServerID = v
	}
	if n, ok := intField(scalars, "punishment-mode"); ok {
		c.PunishmentMode = clampPunishment(n)
	}
	if n, ok := intField(scalars, "confidence-threshold"); ok {
		c.ConfidenceThreshold = n
	}
	if f, ok := floatField(scalars, "max-reach-distance"); ok {
		c.MaxReachDistance = f
	}
	if n, ok := intField(scalars, "max-cps"); ok {
		c.MaxCPS = n
	}
	if f, ok := floatField(scalars, "max-horizontal-speed-per-tick"); ok {
		c.MaxHorizontalSpeedPerTick = f
	}
	if n, ok := intField(scalars, "max-place-per-tick"); ok {
		c.MaxPlacePerTick = n
	}
	if n, ok := intField(scalars, "fly-airborne-ticks-trigger"); ok {
		c.FlyAirborneTicksTrigger = n
	}
	if l, ok := lists["enabled-checks"]; ok && len(l) > 0 {
		c.EnabledChecks = l
	}
	if v, ok := scalars["ptv.url"]; ok {
		c.PTV.URL = v
	}
	if v, ok := scalars["ptv.secret"]; ok {
		c.PTV.Secret = v
	}
	if v, ok := scalars["ptv.server-id"]; ok {
		c.PTV.ServerID = v
	}
	return c, nil
}

// MaxPunishment 返回配置允许的最高处置级别。
func (c Config) MaxPunishment() model.PunishmentType {
	switch c.PunishmentMode {
	case 1:
		return model.PunishmentKick
	case 2:
		return model.PunishmentBan
	default:
		return model.PunishmentAlert
	}
}

// EffectiveServerID 返回上报用的服务端标识：优先 ptv.server-id，其次顶层 server-id。
func (c Config) EffectiveServerID() string {
	if strings.TrimSpace(c.PTV.ServerID) != "" {
		return c.PTV.ServerID
	}
	return c.ServerID
}

// IsEnabled 判断某检查项分类是否启用。
func (c Config) IsEnabled(check string) bool {
	for _, e := range c.EnabledChecks {
		if strings.EqualFold(strings.TrimSpace(e), check) {
			return true
		}
	}
	return false
}

// clampPunishment 把越界的处置级别收敛到合法范围，避免配置笔误导致意外处置。
func clampPunishment(n int) int {
	if n < 0 {
		return 0
	}
	if n > 2 {
		return 2
	}
	return n
}

func intField(m map[string]string, key string) (int, bool) {
	v, ok := m[key]
	if !ok {
		return 0, false
	}
	n, err := strconv.Atoi(strings.TrimSpace(v))
	if err != nil {
		return 0, false
	}
	return n, true
}

func floatField(m map[string]string, key string) (float64, bool) {
	v, ok := m[key]
	if !ok {
		return 0, false
	}
	f, err := strconv.ParseFloat(strings.TrimSpace(v), 64)
	if err != nil {
		return 0, false
	}
	return f, true
}

// parseYAML 解析最小 Yaml 子集，返回标量（键为 "section.key" 或顶层 "key"）与列表。
func parseYAML(src string) (map[string]string, map[string][]string) {
	scalars := map[string]string{}
	lists := map[string][]string{}
	section := ""
	pendingList := ""

	for _, raw := range strings.Split(src, "\n") {
		line := strings.TrimRight(raw, "\r")
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}
		indent := len(line) - len(strings.TrimLeft(line, " \t"))

		if strings.HasPrefix(trimmed, "- ") {
			if pendingList != "" {
				lists[pendingList] = append(lists[pendingList], unquote(strings.TrimSpace(trimmed[2:])))
			}
			continue
		}

		idx := strings.Index(trimmed, ":")
		if idx < 0 {
			continue
		}
		key := strings.TrimSpace(trimmed[:idx])
		val := stripInlineComment(strings.TrimSpace(trimmed[idx+1:]))

		if indent == 0 {
			if val == "" {
				section = key
				pendingList = key
			} else {
				scalars[key] = unquote(val)
				section = ""
				pendingList = ""
			}
			continue
		}

		fullKey := key
		if section != "" {
			fullKey = section + "." + key
		}
		if val == "" {
			pendingList = fullKey
		} else {
			scalars[fullKey] = unquote(val)
			pendingList = ""
		}
	}
	return scalars, lists
}

// stripInlineComment 去掉值后面的 " #..." 行内注释。
func stripInlineComment(v string) string {
	if i := strings.Index(v, " #"); i >= 0 {
		return strings.TrimSpace(v[:i])
	}
	return v
}

// unquote 去掉值两侧成对的单 / 双引号。
func unquote(v string) string {
	if len(v) >= 2 {
		if (v[0] == '"' && v[len(v)-1] == '"') || (v[0] == '\'' && v[len(v)-1] == '\'') {
			return v[1 : len(v)-1]
		}
	}
	return v
}
