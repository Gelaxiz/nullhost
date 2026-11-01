package database

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"nullhost/internal/models"
)

func setupTestDB(t *testing.T) (*DB, func()) {
	t.Helper()
	tmpDir, err := os.MkdirTemp("", "nullhost-db-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}

	dbPath := filepath.Join(tmpDir, "test.db")
	db, err := OpenDB(dbPath)
	if err != nil {
		os.RemoveAll(tmpDir)
		t.Fatalf("failed to open test db: %v", err)
	}

	cleanup := func() {
		db.Close()
		os.RemoveAll(tmpDir)
	}

	return db, cleanup
}

func TestDB_WALModeAndSchema(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	var mode string
	err := db.conn.QueryRow("PRAGMA journal_mode;").Scan(&mode)
	if err != nil {
		t.Fatalf("failed to query journal_mode: %v", err)
	}
	if mode != "wal" {
		t.Errorf("expected journal_mode 'wal', got %q", mode)
	}
}

func TestDB_RetiredIDsPermanence(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	ctx := context.Background()
	testID := "my-retired-link-123"

	// 1. Initially should be available
	if status := db.CheckIDStatus(ctx, testID); status != "available" {
		t.Errorf("expected status 'available', got %q", status)
	}

	// 2. Retire ID
	if err := db.RetireID(ctx, testID); err != nil {
		t.Fatalf("failed to retire ID: %v", err)
	}

	// 3. Status must immediately be 'retired'
	if status := db.CheckIDStatus(ctx, testID); status != "retired" {
		t.Errorf("expected status 'retired', got %q", status)
	}

	// 4. Inserting item with retired ID must fail with ErrIDRetired
	item := &models.Item{
		ID:        testID,
		Filename:  "test.txt",
		Type:      "file",
		Size:      100,
		CreatedAt: time.Now().Unix(),
		ExpiresAt: time.Now().Unix() + 3600,
	}
	if err := db.InsertItem(ctx, item); err != ErrIDRetired {
		t.Errorf("expected ErrIDRetired, got %v", err)
	}
}

func TestDB_GenerateIDSkipsRetired(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	ctx := context.Background()

	// Pre-retire 5 IDs
	retiredIDs := []string{"id000001", "id000002", "id000003"}
	if err := db.RetireIDs(ctx, retiredIDs); err != nil {
		t.Fatalf("failed to retire IDs: %v", err)
	}

	for _, id := range retiredIDs {
		isRetired, err := db.IsRetired(ctx, id)
		if err != nil || !isRetired {
			t.Errorf("expected %q to be retired", id)
		}
	}

	// Generate 10 IDs and ensure none are retired
	for i := 0; i < 10; i++ {
		genID, err := db.GenerateID(ctx, "both")
		if err != nil {
			t.Fatalf("failed to generate ID: %v", err)
		}
		if len(genID) != 8 {
			t.Errorf("expected 8-char id, got %q", genID)
		}
		if isRet, _ := db.IsRetired(ctx, genID); isRet {
			t.Errorf("generated ID %q was in retired_ids list!", genID)
		}
	}
}

func TestDB_DeleteRetiresID(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	ctx := context.Background()
	item := &models.Item{
		ID:        "file-to-delete",
		Filename:  "document.pdf",
		Type:      "file",
		Size:      1024,
		CreatedAt: time.Now().Unix(),
		ExpiresAt: time.Now().Unix() + 3600,
	}

	if err := db.InsertItem(ctx, item); err != nil {
		t.Fatalf("failed to insert item: %v", err)
	}

	// Delete item
	if err := db.DeleteItem(ctx, item.ID); err != nil {
		t.Fatalf("failed to delete item: %v", err)
	}

	// Must be retired now
	if status := db.CheckIDStatus(ctx, item.ID); status != "retired" {
		t.Errorf("expected status 'retired' after deletion, got %q", status)
	}

	// Item must not be found
	_, err := db.GetItem(ctx, item.ID)
	if err != ErrNotFound {
		t.Errorf("expected ErrNotFound, got %v", err)
	}
}
