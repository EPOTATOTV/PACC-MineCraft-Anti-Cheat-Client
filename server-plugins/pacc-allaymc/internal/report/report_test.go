package report

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/EPOTATOTV/PACC4_0/server-plugins/pacc-allaymc/internal/model"
)

func TestSignDeterministicAndFormat(t *testing.T) {
	r := NewHTTP("https://ptv.example.com", "secret-key")
	body := []byte(`{"a":1}`)
	s1 := r.Sign(body)
	s2 := r.Sign(body)
	if s1 != s2 {
		t.Fatalf("同输入签名应稳定，%s != %s", s1, s2)
	}
	if len(s1) != 64 {
		t.Fatalf("HMAC-SHA256 十六进制签名应为 64 字符，实际 %d", len(s1))
	}
	// 与标准库独立计算的结果逐字节一致。
	mac := hmac.New(sha256.New, []byte("secret-key"))
	mac.Write(body)
	if want := hex.EncodeToString(mac.Sum(nil)); s1 != want {
		t.Fatalf("签名不匹配标准库结果：%s != %s", s1, want)
	}
}

func TestSignDiffersByKey(t *testing.T) {
	body := []byte("payload")
	a := NewHTTP("", "key-a").Sign(body)
	b := NewHTTP("", "key-b").Sign(body)
	if a == b {
		t.Fatal("不同密钥应产生不同签名")
	}
}

func TestBuildEventPayloadFields(t *testing.T) {
	raw, err := BuildEventPayload(model.DetectionEvent{
		ServerID:    "srv-1",
		PlayerUUID:  "uuid-1",
		PlayerName:  "Steve",
		CheckType:   "MOVE",
		CheatType:   "SPEED",
		Confidence:  80,
		Detail:      "detail",
		TimestampMs: 12345,
	})
	if err != nil {
		t.Fatalf("序列化失败: %v", err)
	}
	got := decode(t, raw)
	assertField(t, got, "serverId", "srv-1")
	assertField(t, got, "playerUuid", "uuid-1")
	assertField(t, got, "checkType", "MOVE")
	assertField(t, got, "cheatType", "SPEED")
	if got["confidence"].(float64) != 80 {
		t.Errorf("confidence = %v", got["confidence"])
	}
	if got["timestampMs"].(float64) != 12345 {
		t.Errorf("timestampMs = %v", got["timestampMs"])
	}
}

func TestBuildRegisterPayloadFields(t *testing.T) {
	raw, err := BuildRegisterPayload(model.ServerInfo{
		ServerID:      "srv-1",
		ServerType:    model.ServerTypeAllayMC,
		PluginVersion: "6.0.0",
		ServerVersion: "1.21.0",
		ServerName:    "Bedrock",
		MaxPlayers:    100,
	})
	if err != nil {
		t.Fatalf("序列化失败: %v", err)
	}
	got := decode(t, raw)
	assertField(t, got, "serverType", "ALLAYMC")
	assertField(t, got, "pluginVersion", "6.0.0")
	if got["maxPlayers"].(float64) != 100 {
		t.Errorf("maxPlayers = %v", got["maxPlayers"])
	}
}

func TestBuildPunishmentPayloadFields(t *testing.T) {
	raw, err := BuildPunishmentPayload("srv-1", "uuid-1", model.PunishmentKick, "cheat", 0)
	if err != nil {
		t.Fatalf("序列化失败: %v", err)
	}
	got := decode(t, raw)
	assertField(t, got, "type", "KICK")
	assertField(t, got, "reason", "cheat")
	if got["durationMs"].(float64) != 0 {
		t.Errorf("durationMs = %v", got["durationMs"])
	}
}

func TestNoopDoesNotPanic(t *testing.T) {
	var r Reporter = Noop{}
	r.Register(model.ServerInfo{})
	r.Report(model.DetectionEvent{})
	r.ReportPunishment("s", "p", model.PunishmentAlert, "r", 0)
}

func TestHTTPPostsSignedRequestAsync(t *testing.T) {
	type captured struct {
		path string
		sig  string
		body []byte
	}
	ch := make(chan captured, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		buf := make([]byte, req.ContentLength)
		_, _ = req.Body.Read(buf)
		ch <- captured{path: req.URL.Path, sig: req.Header.Get(SignatureHeader), body: buf}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	r := NewHTTP(srv.URL, "k")
	r.Report(model.DetectionEvent{ServerID: "s", CheckType: "MOVE", CheatType: "SPEED"})

	select {
	case got := <-ch:
		if got.path != pathEvent {
			t.Errorf("应 POST 到 %s，实际 %s", pathEvent, got.path)
		}
		if got.sig != r.Sign(got.body) {
			t.Errorf("X-PACC-Signature 头与请求体签名不一致")
		}
		if !strings.Contains(string(got.body), "SPEED") {
			t.Errorf("请求体缺少 cheatType：%s", got.body)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("上报未在超时内送达（异步发送失败）")
	}
}

func decode(t *testing.T, raw []byte) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatalf("JSON 解析失败: %v (%s)", err, raw)
	}
	return m
}

func assertField(t *testing.T, m map[string]any, key, want string) {
	t.Helper()
	if got, _ := m[key].(string); got != want {
		t.Errorf("%s = %q，期望 %q", key, got, want)
	}
}
