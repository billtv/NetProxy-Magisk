package catalog

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRecoverRollsBackIncompletePair(t *testing.T) {
	root := t.TempDir()
	groupDir := filepath.Join(root, "group")
	stagingDir := filepath.Join(root, "staging", "catalog-crashed")
	if err := os.MkdirAll(stagingDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(groupDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(groupDir, "provider.json"), []byte("new-provider"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(groupDir, "meta.json"), []byte("new-meta"), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"provider.json.bak", "meta.json.bak"} {
		content := "old-provider"
		if strings.HasPrefix(name, "meta") {
			content = "old-meta"
		}
		if err := os.WriteFile(filepath.Join(stagingDir, name), []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(stagingDir, "target"), []byte(groupDir+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(stagingDir, "journal"), []byte("begin\nprovider\nmeta\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := Recover(context.Background(), root); err != nil {
		t.Fatalf("recover: %v", err)
	}
	providerContent, err := os.ReadFile(filepath.Join(groupDir, "provider.json"))
	if err != nil {
		t.Fatal(err)
	}
	if string(providerContent) != "old-provider" {
		t.Fatalf("provider was not rolled back: %q", providerContent)
	}
	metadataContent, err := os.ReadFile(filepath.Join(groupDir, "meta.json"))
	if err != nil {
		t.Fatal(err)
	}
	if string(metadataContent) != "old-meta" {
		t.Fatalf("metadata was not rolled back: %q", metadataContent)
	}
	if _, err := os.Stat(stagingDir); !os.IsNotExist(err) {
		t.Fatalf("staging transaction was not removed: %v", err)
	}
}

func TestRecoverRemovesCommittedPairJournal(t *testing.T) {
	root := t.TempDir()
	txDir := filepath.Join(root, "staging", "catalog-committed")
	if err := os.MkdirAll(txDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(txDir, "journal"), []byte("begin\nprovider\nmeta\ncommit\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := Recover(context.Background(), root); err != nil {
		t.Fatalf("recover committed journal: %v", err)
	}
	if _, err := os.Stat(txDir); !os.IsNotExist(err) {
		t.Fatalf("committed transaction was not cleaned: %v", err)
	}
}

func TestRecoveryAfterRollbackCleanupWasInterrupted(t *testing.T) {
	root := t.TempDir()
	group := filepath.Join(root, "group")
	tx := filepath.Join(root, "staging", "catalog-restored")
	for _, directory := range []string{group, tx} {
		if err := os.MkdirAll(directory, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	for path, content := range map[string]string{
		filepath.Join(group, "provider.json"): "old-provider",
		filepath.Join(group, "meta.json"):     "old-meta",
		filepath.Join(tx, "target"):           group,
		filepath.Join(tx, "journal"):          "begin\nprovider\nmeta\nrolled_back\n",
		filepath.Join(tx, "meta.json.bak"):    "old-meta",
	} {
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	for range 2 {
		if err := Recover(t.Context(), root); err != nil {
			t.Fatal(err)
		}
	}
	content, err := os.ReadFile(filepath.Join(group, "provider.json"))
	if err != nil || string(content) != "old-provider" {
		t.Fatalf("已恢复 Provider 被删除: %q %v", content, err)
	}
}

func TestRecoverRemovesIncompleteJournal(t *testing.T) {
	root := t.TempDir()
	txDir := filepath.Join(root, "staging", "catalog-incomplete")
	if err := os.MkdirAll(txDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(txDir, "journal"), []byte("begin\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := Recover(context.Background(), root); err != nil {
		t.Fatalf("recover incomplete journal: %v", err)
	}
	if _, err := os.Stat(txDir); !os.IsNotExist(err) {
		t.Fatalf("incomplete transaction was not removed: %v", err)
	}
}

func TestRecoveryCanRetryAfterPartialRestore(t *testing.T) {
	root := t.TempDir()
	group := filepath.Join(root, "group")
	tx := filepath.Join(root, "staging", "catalog-retry")
	for _, directory := range []string{group, tx, filepath.Join(group, "meta.json")} {
		if err := os.MkdirAll(directory, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	for path, content := range map[string]string{
		filepath.Join(tx, "target"):                  group,
		filepath.Join(tx, "journal"):                 "begin\nprovider\nmeta\n",
		filepath.Join(tx, "provider.json.bak"):       "old-provider",
		filepath.Join(tx, "meta.json.bak"):           "old-meta",
		filepath.Join(group, "provider.json"):        "new-provider",
		filepath.Join(group, "meta.json", "blocked"): "block replacement",
	} {
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	for range 2 {
		if err := Recover(context.Background(), root); err == nil {
			t.Fatal("恢复失败未返回错误")
		}
		for _, name := range []string{"journal", "provider.json.bak", "meta.json.bak"} {
			if _, err := os.Stat(filepath.Join(tx, name)); err != nil {
				t.Fatal(err)
			}
		}
		content, err := os.ReadFile(filepath.Join(group, "provider.json"))
		if err != nil || string(content) != "old-provider" {
			t.Fatalf("已恢复 Provider 丢失: %q %v", content, err)
		}
	}
	if err := os.RemoveAll(filepath.Join(group, "meta.json")); err != nil {
		t.Fatal(err)
	}
	if err := Recover(context.Background(), root); err != nil {
		t.Fatal(err)
	}
	if err := Recover(context.Background(), root); err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(filepath.Join(group, "meta.json"))
	if err != nil || string(content) != "old-meta" {
		t.Fatalf("metadata 未恢复: %q %v", content, err)
	}
	if _, err := os.Stat(tx); !os.IsNotExist(err) {
		t.Fatalf("成功后未清理: %v", err)
	}
}

func TestRecoverPreservesBackupsWithUnreadableJournal(t *testing.T) {
	root := t.TempDir()
	tx := filepath.Join(root, "staging", "catalog-unreadable")
	if err := os.MkdirAll(filepath.Join(tx, "journal"), 0o700); err != nil {
		t.Fatal(err)
	}
	backup := filepath.Join(tx, "provider.json.bak")
	if err := os.WriteFile(backup, []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := Recover(context.Background(), root); err == nil {
		t.Fatal("日志不可读未返回错误")
	}
	if _, err := os.Stat(backup); err != nil {
		t.Fatal(err)
	}
}
