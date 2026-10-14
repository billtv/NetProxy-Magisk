package httpapi

import (
	"context"
	"encoding/json/jsontext"
	json "encoding/json/v2"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/androidplatform"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/paths"
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

func TestAndroidAppsPreparePrivateCacheAndRemainCancellable(t *testing.T) {
	s := resourceFixture(t)
	calls := 0
	s.platform = platformFunc(func(ctx context.Context, request androidplatform.Request) (jsontext.Value, error) {
		calls++
		if ctx != nil && ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if request.Operation != "apps" || request.UserID != 10 || request.Size != 96 || request.IconCache != filepath.Join(paths.New(s.options.ModuleDir).AndroidIcons(), "10", "96") {
			t.Fatal(request)
		}
		return jsontext.Value(`[]`), nil
	})
	for range cap(s.writeSlots) {
		s.writeSlots <- struct{}{}
	}
	for index, query := range []string{"user_id=10&size=96", "size=96", "user_id=10&size=999", "user_id=10&size=96&icon_cache=/secret", "user_id=21475&size=96"} {
		r := httptest.NewRequest("GET", "/api/v1/android/apps?"+query, nil)
		r.Host = s.config.Listen
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Authorization", "Bearer "+s.config.Secret)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		want := 400
		if index == 0 {
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
	_, err := s.queryPlatform(cancelled, androidplatform.Request{Operation: "apps"})
	if err == nil {
		t.Fatal("平台查询吞掉取消")
	}
	connection, err := json.Marshal(s.config.Connection())
	if err != nil || strings.Contains(string(connection), "private_key") || strings.Contains(string(connection), "PRIVATE KEY") {
		t.Fatal("客户端连接资料包含私钥")
	}
}

func TestAndroidIconReadsCachedFileWithoutPlatformAndSupportsETag(t *testing.T) {
	s := resourceFixture(t)
	s.platform = platformFunc(func(ctx context.Context, request androidplatform.Request) (jsontext.Value, error) {
		t.Fatal("缓存读取不应启动 Android 进程")
		return nil, nil
	})
	key := strings.Repeat("a", 64)
	cache := filepath.Join(paths.New(s.options.ModuleDir).AndroidIcons(), "0", "96")
	if err := os.MkdirAll(cache, 0o700); err != nil {
		t.Fatal(err)
	}
	png := "\x89PNG\r\n\x1a\nfixture"
	if err := os.WriteFile(filepath.Join(cache, key+".png"), []byte(png), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		path, method, token, etag string
		status                    int
	}{
		{"/api/v1/android/icons/0/96/" + key, "GET", s.config.Secret, "", 200},
		{"/api/v1/android/icons/0/96/" + key, "GET", s.config.Secret, `"` + key + `"`, 304},
		{"/api/v1/android/icons/0/96/" + key, "HEAD", s.config.Secret, "", 405},
		{"/api/v1/android/icons/0/96/" + key, "GET", "wrong", "", 401},
		{"/api/v1/android/icons/0/96/" + strings.Repeat("b", 64), "GET", s.config.Secret, "", 404},
		{"/api/v1/android/icons/0/999/" + key, "GET", s.config.Secret, "", 400},
		{"/api/v1/android/icons/0/96/" + key + "?file=secret", "GET", s.config.Secret, "", 400},
		{"/api/v1/android/apps/icons", "POST", s.config.Secret, "", 404},
	} {
		r := httptest.NewRequest(tc.method, tc.path, nil)
		r.Host = s.config.Listen
		r.Header.Set("Authorization", "Bearer "+tc.token)
		r.Header.Set("If-None-Match", tc.etag)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		if w.Code != tc.status {
			t.Fatalf("%s %s: %d %s", tc.method, tc.path, w.Code, w.Body.String())
		}
		if tc.status == 200 && (w.Body.String() != png || w.Header().Get("Content-Type") != "image/png") {
			t.Fatal("图标未按二进制返回")
		}
	}
}
