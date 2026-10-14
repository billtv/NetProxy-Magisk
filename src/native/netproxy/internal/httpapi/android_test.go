package httpapi

import (
	"context"
	"encoding/json/jsontext"
	json "encoding/json/v2"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/androidplatform"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/paths"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/processlock"
)

type platformFunc func(context.Context, androidplatform.Request) (jsontext.Value, error)

func (f platformFunc) Query(ctx context.Context, request androidplatform.Request) (jsontext.Value, error) {
	return f(ctx, request)
}

func androidResourceRequest(s *Server, method, path, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.Host = s.config.Listen
	r.Header.Set("Content-Type", "application/json")
	if path != "/api/v1/auth/provision" {
		r.Header.Set("Authorization", "Bearer "+s.config.Secret)
	}
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	return w
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

func TestProvisionCooldownBackoffAndRecovery(t *testing.T) {
	s := resourceFixture(t)
	calls := 0
	failing := true
	s.platform = platformFunc(func(ctx context.Context, request androidplatform.Request) (jsontext.Value, error) {
		calls++
		deadline, ok := ctx.Deadline()
		if !ok || time.Until(deadline) > 15*time.Second {
			t.Fatal("主动交付没有有界时限")
		}
		if failing {
			return nil, errors.New("平台查询失败 fixture")
		}
		return jsontext.Value(`{"delivered":true}`), nil
	})
	for _, delay := range []time.Duration{3, 6, 12, 24, 30, 30} {
		// 模拟启动时无正文的主动交付，后续请求与它共享退避状态。
		r := httptest.NewRequest(http.MethodPost, "/api/v1/auth/provision", nil)
		if _, err := s.provisionClient(t.Context(), r); err == nil {
			t.Fatal("交付失败被当作成功")
		}
		want := delay * time.Second
		if s.provisionBackoff != want || time.Until(s.provisionAfter) < want-time.Second || time.Until(s.provisionAfter) > want {
			t.Fatalf("退避: %v, 剩余: %v, 期望: %v", s.provisionBackoff, time.Until(s.provisionAfter), want)
		}
		after := s.provisionAfter
		before := calls
		w := androidResourceRequest(s, http.MethodPost, "/api/v1/auth/provision", `{}`)
		if w.Code != 409 || calls != before || !s.provisionAfter.Equal(after) {
			t.Fatal("冷却请求启动进程或延长窗口", w.Code, calls)
		}
		s.provisionAfter = time.Now().Add(-time.Second)
	}
	failing = false
	w := androidResourceRequest(s, http.MethodPost, "/api/v1/auth/provision", `{}`)
	if w.Code != 200 || s.provisionBackoff != 0 || time.Until(s.provisionAfter) < 29*time.Second || time.Until(s.provisionAfter) > 30*time.Second {
		t.Fatal("成功没有重置退避并进入 30 秒冷却", w.Code, s.provisionBackoff)
	}
	before := calls
	if w := androidResourceRequest(s, http.MethodPost, "/api/v1/auth/provision", `{}`); w.Code != 409 || calls != before {
		t.Fatal("成功后的冷却失效", w.Code, calls)
	}
	s.provisionAfter = time.Now().Add(-time.Second)
	if w := androidResourceRequest(s, http.MethodPost, "/api/v1/auth/provision", `{}`); w.Code != 200 || calls != before+1 {
		t.Fatal("冷却后不能重新交付", w.Code, calls)
	}
	s.provisionAfter = time.Now().Add(-time.Second)
	failing = true
	androidResourceRequest(s, http.MethodPost, "/api/v1/auth/provision", `{}`)
	if s.provisionBackoff != 3*time.Second {
		t.Fatal("成功后的首次失败沿用了旧退避", s.provisionBackoff)
	}
}

func TestProvisionConcurrentRequestsDoNotStartDuplicateProcesses(t *testing.T) {
	s := resourceFixture(t)
	var calls atomic.Int32
	entered, release := make(chan struct{}), make(chan struct{})
	var once sync.Once
	defer once.Do(func() { close(release) })
	s.platform = platformFunc(func(ctx context.Context, request androidplatform.Request) (jsontext.Value, error) {
		if calls.Add(1) == 1 {
			close(entered)
		}
		select {
		case <-release:
			return jsontext.Value(`{"delivered":true}`), nil
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	})
	done := make(chan *httptest.ResponseRecorder, 1)
	go func() { done <- androidResourceRequest(s, http.MethodPost, "/api/v1/auth/provision", `{}`) }()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("首次交付未启动")
	}
	var concurrent sync.WaitGroup
	for range 32 {
		concurrent.Go(func() {
			if w := androidResourceRequest(s, http.MethodPost, "/api/v1/auth/provision", `{}`); w.Code != 409 {
				t.Error("并发交付未拒绝", w.Code)
			}
		})
	}
	concurrent.Wait()
	once.Do(func() { close(release) })
	if w := <-done; w.Code != 200 || calls.Load() != 1 {
		t.Fatal("并发重复启动或首次交付失败", w.Code, calls.Load())
	}
}

func TestProvisionInvalidAndCancelledRequestsDoNotConsumeCooldown(t *testing.T) {
	s := resourceFixture(t)
	s.platform = platformFunc(func(context.Context, androidplatform.Request) (jsontext.Value, error) {
		t.Fatal("无效请求启动平台进程")
		return nil, nil
	})
	for _, body := range []string{`{"connection":{}}`, `{"user_id":0}`, `{"package":"example"}`, `[]`, `null`, `{"x":1,"x":2}`} {
		if w := androidResourceRequest(s, http.MethodPost, "/api/v1/auth/provision", body); w.Code != 400 {
			t.Fatal("无效正文未拒绝", w.Code)
		}
	}
	if w := androidResourceRequest(s, http.MethodPost, "/api/v1/auth/provision", strings.Repeat(" ", 1025)); w.Code != 413 {
		t.Fatal("超长正文未拒绝", w.Code)
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, err := s.provisionClient(ctx, httptest.NewRequest(http.MethodPost, "/api/v1/auth/provision", nil)); !errors.Is(err, context.Canceled) {
		t.Fatal("交付吞掉取消", err)
	}
	if !s.provisionAfter.IsZero() || s.provisionBackoff != 0 {
		t.Fatal("无效或取消请求消耗冷却")
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

func TestAndroidAppsUseOnlyFourIconSizeBuckets(t *testing.T) {
	s := resourceFixture(t)
	wantSize := 0
	calls := 0
	s.platform = platformFunc(func(ctx context.Context, request androidplatform.Request) (jsontext.Value, error) {
		calls++
		wantCache := ""
		if wantSize != 0 {
			wantCache = filepath.Join(paths.New(s.options.ModuleDir).AndroidIcons(), "10", strconv.Itoa(wantSize))
		}
		if request.Operation != "apps" || request.UserID != 10 || request.Size != wantSize || request.IconCache != wantCache {
			t.Fatalf("分档请求: %+v, 期望尺寸: %d", request, wantSize)
		}
		return jsontext.Value(`{"user_id":10,"revision":"fixture","apps":[]}`), nil
	})
	if w := androidResourceRequest(s, http.MethodGet, "/api/v1/android/apps?user_id=10", ""); w.Code != 200 {
		t.Fatal("不请求图标的清单失败", w.Code)
	}
	if _, err := os.Stat(paths.New(s.options.ModuleDir).AndroidIcons()); !os.IsNotExist(err) {
		t.Fatal("无尺寸请求创建了图标目录", err)
	}
	for size := 16; size <= 192; size++ {
		switch {
		case size <= 48:
			wantSize = 48
		case size <= 96:
			wantSize = 96
		case size <= 144:
			wantSize = 144
		default:
			wantSize = 192
		}
		w := androidResourceRequest(s, http.MethodGet, "/api/v1/android/apps?user_id=10&size="+strconv.Itoa(size), "")
		if w.Code != 200 {
			t.Fatalf("尺寸 %d: %d %s", size, w.Code, w.Body.String())
		}
	}
	for _, query := range []string{
		"user_id=10&size=0", "user_id=10&size=15", "user_id=10&size=193", "user_id=10&size=-48",
		"user_id=10&size=", "user_id=10&size=48.0", "user_id=10&size=../../48",
		"user_id=10&size=9999999999999999999999999999999999999", "user_id=10&size=48&size=96",
		"user_id=-1&size=48", "user_id=21475&size=48", "user_id=10&size=48&icon_cache=/secret",
	} {
		if w := androidResourceRequest(s, http.MethodGet, "/api/v1/android/apps?"+query, ""); w.Code != 400 {
			t.Fatal("恶意参数未拒绝", query, w.Code)
		}
	}
	if calls != 178 {
		t.Fatal("无效请求进入平台或有效请求丢失", calls)
	}
	entries, err := os.ReadDir(filepath.Join(paths.New(s.options.ModuleDir).AndroidIcons(), "10"))
	if err != nil || len(entries) != 8 {
		t.Fatal("没有收敛为四个目录与四个锁", len(entries), err)
	}
	for _, entry := range entries {
		name := strings.TrimSuffix(entry.Name(), ".lock")
		if name != "48" && name != "96" && name != "144" && name != "192" {
			t.Fatal("创建了非档位目录或锁", entry.Name())
		}
	}
}

func TestAndroidAppsSameBucketSharesCancellableCacheLock(t *testing.T) {
	s := resourceFixture(t)
	cache := filepath.Join(paths.New(s.options.ModuleDir).AndroidIcons(), "10", "48")
	lock, err := processlock.Acquire(t.Context(), cache+".lock")
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Release()
	s.platform = platformFunc(func(context.Context, androidplatform.Request) (jsontext.Value, error) {
		t.Error("同档位没有复用已持有的缓存锁")
		return jsontext.Value(`[]`), nil
	})
	for _, size := range []string{"16", "47", "48"} {
		ctx, cancel := context.WithTimeout(t.Context(), 50*time.Millisecond)
		r := httptest.NewRequest(http.MethodGet, "/api/v1/android/apps?user_id=10&size="+size, nil).WithContext(ctx)
		r.Host = s.config.Listen
		r.Header.Set("Authorization", "Bearer "+s.config.Secret)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		cancel()
		if w.Code != 504 {
			t.Fatal("等待缓存锁没有遵循取消", size, w.Code, w.Body.String())
		}
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
		{"/api/v1/android/icons/0/16/" + key, "GET", s.config.Secret, "", 400},
		{"/api/v1/android/icons/0/140/" + key, "GET", s.config.Secret, "", 400},
		{"/api/v1/android/icons/0/193/" + key, "GET", s.config.Secret, "", 400},
		{"/api/v1/android/icons/0/-48/" + key, "GET", s.config.Secret, "", 400},
		{"/api/v1/android/icons/21475/96/" + key, "GET", s.config.Secret, "", 400},
		{"/api/v1/android/icons/0/96/..%2Fsecret", "GET", s.config.Secret, "", 400},
		{"/api/v1/android/icons/0/96/" + strings.Repeat("A", 64), "GET", s.config.Secret, "", 400},
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

func TestAndroidIconReadsAllBucketsButNeverOldSizeDirectories(t *testing.T) {
	s := resourceFixture(t)
	s.platform = platformFunc(func(context.Context, androidplatform.Request) (jsontext.Value, error) {
		t.Fatal("读取图标启动平台进程")
		return nil, nil
	})
	key := strings.Repeat("a", 64)
	for _, size := range []int{48, 96, 140, 144, 192} {
		cache := filepath.Join(paths.New(s.options.ModuleDir).AndroidIcons(), "10", strconv.Itoa(size))
		if err := os.MkdirAll(cache, 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(cache, key+".png"), []byte("fixture"), 0o600); err != nil {
			t.Fatal(err)
		}
		w := androidResourceRequest(s, http.MethodGet, "/api/v1/android/icons/10/"+strconv.Itoa(size)+"/"+key, "")
		want := 200
		if size == 140 {
			want = 400
		}
		if w.Code != want {
			t.Fatal("图标读取使用了非档位或遗漏有效档位", size, w.Code)
		}
	}
}

func TestAndroidAppsBucketIconKeyCanBeReadUnchanged(t *testing.T) {
	s := resourceFixture(t)
	key := "10/144/" + strings.Repeat("a", 64)
	png := "\x89PNG\r\n\x1a\nfixture"
	s.platform = platformFunc(func(ctx context.Context, request androidplatform.Request) (jsontext.Value, error) {
		if request.Operation != "apps" || request.Size != 144 || request.UserID != 10 {
			t.Fatal("图标请求没有分档", request)
		}
		if err := os.WriteFile(filepath.Join(request.IconCache, strings.Repeat("a", 64)+".png"), []byte(png), 0o600); err != nil {
			t.Fatal(err)
		}
		return json.Marshal(map[string]any{"apps": []map[string]string{{"icon_key": key}}}, json.Deterministic(true))
	})
	w := androidResourceRequest(s, http.MethodGet, "/api/v1/android/apps?user_id=10&size=140", "")
	var listing struct {
		Data struct {
			Apps []struct {
				IconKey string `json:"icon_key"`
			} `json:"apps"`
		} `json:"data"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &listing); err != nil || w.Code != 200 || len(listing.Data.Apps) != 1 {
		t.Fatal("应用清单无效", w.Code, err)
	}
	if listing.Data.Apps[0].IconKey != key {
		t.Fatal("服务端改写了 opaque key")
	}
	w = androidResourceRequest(s, http.MethodGet, "/api/v1/android/icons/"+listing.Data.Apps[0].IconKey, "")
	if w.Code != 200 || w.Body.String() != png {
		t.Fatal("opaque key 不能直接读取图标", w.Code)
	}
}
