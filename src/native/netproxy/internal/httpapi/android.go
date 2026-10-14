package httpapi

import (
	"context"
	"encoding/json/jsontext"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"time"

	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/androidplatform"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/contract"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/paths"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/processlock"
)

type platformQuerier interface {
	Query(context.Context, androidplatform.Request) (jsontext.Value, error)
}

var androidIconKey = regexp.MustCompile(`^[a-f0-9]{64}$`)

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
	s.registerRoute("GET /api/v1/android/apps", 45*time.Second, false, false, func(ctx context.Context, r *http.Request) (contract.Result, error) {
		query, err := resourceQuery(r, "user_id", "size")
		if err != nil {
			return contract.Result{}, err
		}
		user, err := strconv.ParseUint(query.Get("user_id"), 10, 32)
		if err != nil || user > 21474 {
			return contract.Result{}, invalid("Android 用户 ID 无效")
		}
		size := 0
		if query.Has("size") {
			size, err = strconv.Atoi(query.Get("size"))
			if err != nil || size < 16 || size > 192 {
				return contract.Result{}, invalid("图标尺寸必须为 16–192 像素")
			}
		}
		cache := ""
		if size > 0 {
			cache = filepath.Join(paths.New(s.options.ModuleDir).AndroidIcons(), strconv.FormatUint(user, 10), strconv.Itoa(size))
			if err := os.MkdirAll(cache, 0o700); err != nil {
				return contract.Result{}, err
			}
			lock, err := processlock.Acquire(ctx, cache+".lock")
			if err != nil {
				return contract.Result{}, err
			}
			defer lock.Release()
		}
		data, err := s.queryPlatform(ctx, androidplatform.Request{Operation: "apps", UserID: uint32(user), Size: size, IconCache: cache})
		return contract.Success("android.apps", "Android 应用", data), err
	})
	s.mux.HandleFunc("GET /api/v1/android/icons/{user}/{size}/{key}", s.readAndroidIcon)
}

func (s *Server) readAndroidIcon(w http.ResponseWriter, r *http.Request) {
	user, err := strconv.ParseUint(r.PathValue("user"), 10, 32)
	size, sizeErr := strconv.Atoi(r.PathValue("size"))
	key := r.PathValue("key")
	if _, queryErr := resourceQuery(r); queryErr != nil || err != nil || user > 21474 || sizeErr != nil || size < 16 || size > 192 || !androidIconKey.MatchString(key) {
		s.fail(w, r.Context(), invalid("图标资源标识无效"))
		return
	}
	file, err := os.Open(filepath.Join(paths.New(s.options.ModuleDir).AndroidIcons(), strconv.FormatUint(user, 10), strconv.Itoa(size), key+".png"))
	if err != nil {
		if os.IsNotExist(err) {
			err = &contract.Error{Code: "android.icon.not_found", Message: "图标缓存不存在，请刷新应用清单"}
		}
		s.fail(w, r.Context(), err)
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() == 0 || info.Size() > 256<<10 {
		s.fail(w, r.Context(), invalid("图标缓存无效"))
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "private, max-age=604800, immutable")
	w.Header().Set("ETag", `"`+key+`"`)
	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(15 * time.Second))
	http.ServeContent(w, r, key+".png", info.ModTime(), file)
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
