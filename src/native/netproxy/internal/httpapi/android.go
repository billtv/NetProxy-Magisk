package httpapi

import (
	"context"
	"encoding/json/jsontext"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"time"

	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/androidplatform"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/contract"
)

type platformQuerier interface {
	Query(context.Context, androidplatform.Request) (jsontext.Value, error)
}

var androidPackageName = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$`)

func platformPresent() bool {
	_, err := os.Stat("/system/bin/app_process")
	return err == nil
}

func (s *Server) registerAndroid() {
	s.registerRoute("POST /api/v1/auth/provision", 15*time.Second, false, false, s.provisionClient)
	s.registerRoute("GET /api/v1/android/users", 15*time.Second, false, false, func(ctx context.Context, r *http.Request) (contract.Result, error) {
		if _, err := resourceQuery(r); err != nil {
			return contract.Result{}, err
		}
		data, err := s.queryPlatform(ctx, androidplatform.Request{Operation: "users"})
		return contract.Success("android.users", "Android 用户", data), err
	})
	s.registerRoute("GET /api/v1/android/apps", 15*time.Second, false, false, func(ctx context.Context, r *http.Request) (contract.Result, error) {
		query, err := resourceQuery(r, "user_id")
		if err != nil {
			return contract.Result{}, err
		}
		user, err := strconv.ParseUint(query.Get("user_id"), 10, 32)
		if err != nil || user > 21474 {
			return contract.Result{}, invalid("Android 用户 ID 无效")
		}
		data, err := s.queryPlatform(ctx, androidplatform.Request{Operation: "apps", UserID: uint32(user)})
		return contract.Success("android.apps", "Android 应用", data), err
	})
	s.registerRoute("POST /api/v1/android/apps/icons", 15*time.Second, false, false, func(ctx context.Context, r *http.Request) (contract.Result, error) {
		if _, err := resourceQuery(r); err != nil {
			return contract.Result{}, err
		}
		var request struct {
			UserID   *uint32  `json:"user_id"`
			Packages []string `json:"packages"`
			Size     int      `json:"size"`
		}
		if err := decodeRequestLimit(r, &request, 16<<10); err != nil {
			return contract.Result{}, err
		}
		if request.UserID == nil || *request.UserID > 21474 || len(request.Packages) == 0 || len(request.Packages) > 32 || request.Size < 16 || request.Size > 192 {
			return contract.Result{}, invalid("图标查询需要有效用户、尺寸与最多 32 个应用")
		}
		for _, name := range request.Packages {
			if len(name) > 255 || !androidPackageName.MatchString(name) {
				return contract.Result{}, invalid("Android 包名无效")
			}
		}
		data, err := s.queryPlatform(ctx, androidplatform.Request{Operation: "icons", UserID: *request.UserID, Packages: request.Packages, Size: request.Size})
		return contract.Success("android.icons", "应用图标", data), err
	})
}

func (s *Server) queryPlatform(ctx context.Context, request androidplatform.Request) (jsontext.Value, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	select {
	case s.platformSlots <- struct{}{}:
		defer func() { <-s.platformSlots }()
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	return s.platform.Query(ctx, request)
}

func (s *Server) provisionClient(ctx context.Context, r *http.Request) (contract.Result, error) {
	if !s.provisionMu.TryLock() {
		return contract.Result{}, busy()
	}
	defer s.provisionMu.Unlock()
	if time.Now().Before(s.provisionAfter) {
		return contract.Result{}, busy()
	}
	if _, err := resourceQuery(r); err != nil {
		return contract.Result{}, err
	}
	if r.Body != nil && r.ContentLength != 0 {
		var body struct{}
		if err := decodeRequestLimit(r, &body, 1024); err != nil {
			return contract.Result{}, err
		}
	}
	if err := ctx.Err(); err != nil {
		return contract.Result{}, err
	}
	s.provisionAfter = time.Now().Add(3 * time.Second)
	connection := s.config.Connection()
	_, err := s.queryPlatform(ctx, androidplatform.Request{Operation: "provision", Connection: &connection})
	return contract.Success("api.provisioned", "连接资料已交付到官方管理器", map[string]bool{"delivered": true}), err
}
