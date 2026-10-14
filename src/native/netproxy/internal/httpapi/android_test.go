package httpapi

import (
	"context"
	"encoding/json/jsontext"
	json "encoding/json/v2"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/androidplatform"
)

type platformFunc func(context.Context, androidplatform.Request) (jsontext.Value, error)

func (f platformFunc) Query(ctx context.Context, request androidplatform.Request) (jsontext.Value, error) {
	return f(ctx, request)
}

func TestProvisionOnlyDeliversPrivatelyAndIsLimited(t *testing.T) {
	s := resourceFixture(t)
	calls := 0
	s.platform = platformFunc(func(ctx context.Context, request androidplatform.Request) (jsontext.Value, error) {
		calls++
		if request.Operation != "provision" || request.Connection == nil || *request.Connection != s.config.Connection() {
			t.Fatal("交付未使用服务端固定凭据", request.Operation)
		}
		return jsontext.Value(`{"delivered":true}`), nil
	})
	for _, tc := range []struct {
		host, origin, path, body string
		status                   int
	}{
		{"evil.test", "", "/api/v1/auth/provision", `{}`, 403},
		{s.config.Listen, "https://" + s.config.Listen, "/api/v1/auth/provision", `{}`, 403},
		{s.config.Listen, "", "/api/v1/auth/provision?user_id=0", `{}`, 403},
		{s.config.Listen, "", "/api/v1/auth/provision", `{"package":"evil"}`, 400},
		{s.config.Listen, "", "/api/v1/auth/provision", `{}`, 200},
		{s.config.Listen, "", "/api/v1/auth/provision", `{}`, 409},
	} {
		r := httptest.NewRequest("POST", tc.path, strings.NewReader(tc.body))
		r.Host = tc.host
		r.Header.Set("Content-Type", "application/json")
		if tc.origin != "" {
			r.Header.Set("Origin", tc.origin)
		}
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		if w.Code != tc.status {
			t.Fatalf("%s: %d %s", tc.path, w.Code, w.Body.String())
		}
		if strings.Contains(w.Body.String(), s.config.Secret) || strings.Contains(w.Body.String(), "PRIVATE KEY") {
			t.Fatal("交付响应泄漏秘密")
		}
	}
	if calls != 1 {
		t.Fatal("无效请求进入平台查询", calls)
	}
}

func TestAndroidIconsAreValidatedAndRemainCancellable(t *testing.T) {
	s := resourceFixture(t)
	calls := 0
	s.platform = platformFunc(func(ctx context.Context, request androidplatform.Request) (jsontext.Value, error) {
		calls++
		if ctx != nil && ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if request.Operation != "icons" || request.UserID != 10 || request.Size != 96 || request.Packages[0] != "com.example.app" {
			t.Fatal(request)
		}
		return jsontext.Value(`[]`), nil
	})
	for range cap(s.writeSlots) {
		s.writeSlots <- struct{}{}
	}
	for _, body := range []string{`{"user_id":10,"size":96,"packages":["com.example.app"]}`,
		`{"size":96,"packages":["com.example.app"]}`, `{"user_id":10,"size":999,"packages":["com.example.app"]}`,
		`{"user_id":10,"size":96,"packages":["../../secret"]}`, `{"user_id":10,"size":96,"packages":[]}`} {
		r := httptest.NewRequest("POST", "/api/v1/android/apps/icons", strings.NewReader(body))
		r.Host = s.config.Listen
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Authorization", "Bearer "+s.config.Secret)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		want := 400
		if calls == 1 && strings.Contains(body, `"user_id":10,"size":96,"packages":["com.example.app"]`) {
			want = 200
		}
		if w.Code != want {
			t.Fatal(w.Code, w.Body.String())
		}
	}
	if calls != 1 {
		t.Fatal(calls)
	}
	cancelled, cancel := context.WithCancel(t.Context())
	cancel()
	_, err := s.queryPlatform(cancelled, androidplatform.Request{Operation: "icons"})
	if err == nil {
		t.Fatal("平台查询吞掉取消")
	}
	connection, err := json.Marshal(s.config.Connection())
	if err != nil || strings.Contains(string(connection), "private_key") || strings.Contains(string(connection), "PRIVATE KEY") {
		t.Fatal("客户端连接资料包含私钥")
	}
}

func TestAndroidIconsAcceptFrameworkPackage(t *testing.T) {
	s := resourceFixture(t)
	s.platform = platformFunc(func(ctx context.Context, request androidplatform.Request) (jsontext.Value, error) {
		if request.Operation != "icons" || len(request.Packages) != 1 || request.Packages[0] != "android" {
			t.Fatal(request)
		}
		return jsontext.Value(`[]`), nil
	})
	resourceAssertSuccess(t, s, "POST", "/api/v1/android/apps/icons", `{"user_id":0,"size":96,"packages":["android"]}`, "android.icons", nil)
}
