package cleanup

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"nullhost/internal/database"
	"nullhost/internal/models"
	"nullhost/internal/storage"
)

func TestCleanup_RunCleanupPass(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "nullhost-cleanup-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	dbPath := filepath.Join(tmpDir, "test.db")
	db, err := database.OpenDB(dbPath)
	if err != nil {
		t.Fatalf("failed to open test db: %v", err)
	}
	defer db.Close()

	uploadDir := filepath.Join(tmpDir, "uploads")
	os.MkdirAll(uploadDir, 0755)

	ctx := context.Background()
	now := time.Now().Unix()

	// 1. Expired file item
	expiredFileID := "exp-file-1"
	expiredFileName := "expired.txt"
	expiredFilePath := storage.FilePath(uploadDir, expiredFileID, expiredFileName)
	storage.SaveFile(expiredFilePath, strings.NewReader("expired content"))

	err = db.InsertItem(ctx, &models.Item{
		ID:        expiredFileID,
		Type:      "file",
		Filename:  expiredFileName,
		ExpiresAt: now - 100, // already expired
		CreatedAt: now - 3600,
	})
	if err != nil {
		t.Fatalf("failed to insert expired item: %v", err)
	}

	// 2. Active, unexpired item
	activeID := "active-file-1"
	activeFileName := "active.txt"
	activeFilePath := storage.FilePath(uploadDir, activeID, activeFileName)
	storage.SaveFile(activeFilePath, strings.NewReader("active content"))

	err = db.InsertItem(ctx, &models.Item{
		ID:        activeID,
		Type:      "file",
		Filename:  activeFileName,
		ExpiresAt: now + 3600, // expires in future
		CreatedAt: now,
	})
	if err != nil {
		t.Fatalf("failed to insert active item: %v", err)
	}

	// Run cleanup pass
	count, err := RunCleanupPass(ctx, db, uploadDir)
	if err != nil {
		t.Fatalf("RunCleanupPass failed: %v", err)
	}
	if count != 1 {
		t.Errorf("expected 1 cleaned item, got %d", count)
	}

	// Verify expired file on disk is gone
	if _, err := os.Stat(expiredFilePath); !os.IsNotExist(err) {
		t.Errorf("expected expired file to be deleted from disk")
	}

	// Verify expired file ID is in retired_ids
	isRetired, err := db.IsRetired(ctx, expiredFileID)
	if err != nil || !isRetired {
		t.Errorf("expected expired file ID to be permanently retired")
	}

	// Verify active file on disk and in DB still exists
	if _, err := os.Stat(activeFilePath); os.IsNotExist(err) {
		t.Errorf("active file on disk was unexpectedly deleted")
	}
	item, err := db.GetItem(ctx, activeID)
	if err != nil || item == nil {
		t.Errorf("active item in DB was unexpectedly deleted: %v", err)
	}
}
