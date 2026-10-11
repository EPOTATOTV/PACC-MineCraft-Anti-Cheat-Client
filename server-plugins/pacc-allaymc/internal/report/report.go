// Package report 负责把检测事件与处置上报到 PTV 管控后端。
//
// 请求体用 HMAC-SHA256 签名，十六进制签名放在 X-PACC-Signature 头；发送在独立 goroutine 中异步进行，
// 绝不阻塞服务端主逻辑，失败只记日志。url 为空时使用 Noop，不做任何网络请求。
package report

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/model"
)

// SignatureHeader 存放 HMAC-SHA256 签名的请求头。
const SignatureHeader = "X-PACC-Signature"

const (
	pathRegister   = "/api/v1/server/register"
	pathEvent      = "/api/v1/server/event"
	pathPunishment = "/api/v1/server/punishment"
)

// Reporter 上报通道。
type Reporter interface {
	Register(info model.ServerInfo)
	Report(ev model.DetectionEvent)
	ReportPunishment(serverID, playerUUID string, ptype model.PunishmentType, reason string, durationMs int64)
}

// Noop 不做任何网络请求。
type Noop struct{}

func (Noop) Register(model.ServerInfo) {}

func (Noop) Report(model.DetectionEvent) {}

func (Noop) ReportPunishment(string, string, model.PunishmentType, string, int64) {}

// HTTP 通过 HTTP 上报到 PTV。
type HTTP struct {
	baseURL string
	secret  string
	client  *http.Client
}

// NewHTTP 构造 HTTP 上报通道。baseURL 末尾斜杠会被去掉。
func NewHTTP(baseURL, secret string) *HTTP {
	return &HTTP{
		baseURL: strings.TrimRight(baseURL, "/"),
		secret:  secret,
		client:  &http.Client{Timeout: 10 * time.Second},
	}
}

// Sign 计算请求体的 HMAC-SHA256 十六进制签名。
func (r *HTTP) Sign(body []byte) string {
	mac := hmac.New(sha256.New, []byte(r.secret))
	mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

// Register 上报服务端注册信息。
func (r *HTTP) Register(info model.ServerInfo) {
	body, err := BuildRegisterPayload(info)
	if err != nil {
		return
	}
	r.post(pathRegister, body)
}

// Report 上报一条检测事件。
func (r *HTTP) Report(ev model.DetectionEvent) {
	body, err := BuildEventPayload(ev)
	if err != nil {
		return
	}
	r.post(pathEvent, body)
}

// ReportPunishment 上报一次实际处置（非告警）。
func (r *HTTP) ReportPunishment(serverID, playerUUID string, ptype model.PunishmentType, reason string, durationMs int64) {
	body, err := BuildPunishmentPayload(serverID, playerUUID, ptype, reason, durationMs)
	if err != nil {
		return
	}
	r.post(pathPunishment, body)
}

func (r *HTTP) post(path string, body []byte) {
	if r.baseURL == "" {
		return
	}
	sig := r.Sign(body)
	// 异步发送，不阻塞主逻辑。
	go r.send(path, body, sig)
}

func (r *HTTP) send(path string, body []byte, sig string) {
	req, err := http.NewRequest(http.MethodPost, r.baseURL+path, bytes.NewReader(body))
	if err != nil {
		log.Printf("[PACC] 上报请求构造失败: %v", err)
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(SignatureHeader, sig)
	resp, err := r.client.Do(req)
	if err != nil {
		log.Printf("[PACC] 上报失败: %v", err)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		log.Printf("[PACC] 上报返回非成功状态: %d", resp.StatusCode)
	}
}

// BuildRegisterPayload 生成注册请求体。
func BuildRegisterPayload(info model.ServerInfo) ([]byte, error) {
	return json.Marshal(map[string]any{
		"serverId":      info.ServerID,
		"serverType":    info.ServerType,
		"pluginVersion": info.PluginVersion,
		"serverVersion": info.ServerVersion,
		"serverName":    info.ServerName,
		"maxPlayers":    info.MaxPlayers,
	})
}

// BuildEventPayload 生成检测事件请求体。
func BuildEventPayload(ev model.DetectionEvent) ([]byte, error) {
	return json.Marshal(map[string]any{
		"serverId":    ev.ServerID,
		"playerUuid":  ev.PlayerUUID,
		"playerName":  ev.PlayerName,
		"pteid":       ev.PTEID,
		"checkType":   ev.CheckType,
		"cheatType":   ev.CheatType,
		"confidence":  ev.Confidence,
		"detail":      ev.Detail,
		"timestampMs": ev.TimestampMs,
	})
}

// BuildPunishmentPayload 生成处置请求体。
func BuildPunishmentPayload(serverID, playerUUID string, ptype model.PunishmentType, reason string, durationMs int64) ([]byte, error) {
	return json.Marshal(map[string]any{
		"serverId":   serverID,
		"playerUuid": playerUUID,
		"type":       string(ptype),
		"reason":     reason,
		"durationMs": durationMs,
	})
}
