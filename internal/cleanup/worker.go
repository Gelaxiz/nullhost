package cleanup

import (
	"context"
	"log"
	"os"
	"time"

	"nullhost/internal/database"
	"nullhost/internal/storage"
)

// RunCleanupPass performs a single synchronous cleanup cycle:
// 1. Removes expired items and partially uploaded items older than 24h
// 2. Removes expired tombstones
// 3. Retires all removed IDs permanently
// 4. Deletes orphaned files from the uploads directory older than 24h
func RunCleanupPass(ctx context.Context, db *database.DB, uploadDir string) (int, error) {
	now := time.Now().Unix()
	cleanedCount := 0

	// 1. Fetch expired items or stale partial chunk uploads
	rows, err := db.Conn().QueryContext(ctx, `
		SELECT id, type, COALESCE(filename, '')
		FROM items
		WHERE (expires_at <= ?) OR (is_partial = 1 AND created_at <= ?)`,
		now, now-86400,
	)
	if err != nil {
		return 0, err
	}

	type ExpiredItem struct {
		ID       string
		Type     string
		Filename string
	}
	var expired []ExpiredItem

	for rows.Next() {
		var it ExpiredItem
		if err := rows.Scan(&it.ID, &it.Type, &it.Filename); err == nil {
			expired = append(expired, it)
		}
	}
	rows.Close()

	for _, it := range expired {
		if it.Type == "file" && it.Filename != "" {
			fPath := storage.FilePath(uploadDir, it.ID, it.Filename)
			_ = storage.DeleteFile(fPath)
		}
		_ = db.RetireID(ctx, it.ID)
		_, _ = db.Conn().ExecContext(ctx, "DELETE FROM items WHERE id = ?", it.ID)
		cleanedCount++
	}

	// 2. Fetch and clean expired tombstones
	tombRows, err := db.Conn().QueryContext(ctx, "SELECT id FROM tombstones WHERE expires_at <= ?", now)
	if err == nil {
		var expiredTombs []string
		for tombRows.Next() {
			var tid string
			if err := tombRows.Scan(&tid); err == nil {
				expiredTombs = append(expiredTombs, tid)
			}
		}
		tombRows.Close()

		for _, tid := range expiredTombs {
			_ = db.RetireID(ctx, tid)
		}
		if len(expiredTombs) > 0 {
			_, _ = db.Conn().ExecContext(ctx, "DELETE FROM tombstones WHERE expires_at <= ?", now)
			cleanedCount += len(expiredTombs)
		}
	}

	// 3. Clean up orphan disk files older than 24h
	if uploadDir != "" {
		activeRows, err := db.Conn().QueryContext(ctx, "SELECT id, COALESCE(filename, '') FROM items WHERE type = 'file'")
		if err == nil {
			validFiles := make(map[string]bool)
			for activeRows.Next() {
				var id, fname string
				if err := activeRows.Scan(&id, &fname); err == nil && fname != "" {
					validFiles[id+"_"+storage.SanitizeFilename(fname)] = true
				}
			}
			activeRows.Close()

			entries, err := os.ReadDir(uploadDir)
			if err == nil {
				for _, entry := range entries {
					if entry.IsDir() {
						continue
					}
					name := entry.Name()
					if !validFiles[name] {
						info, err := entry.Info()
						if err == nil && now-info.ModTime().Unix() > 86400 {
							_ = os.Remove(storage.FilePath(uploadDir, "", name))
						}
					}
				}
			}
		}
	}

	return cleanedCount, nil
}

// StartCleanupWorker starts background goroutine ticking every interval
func StartCleanupWorker(ctx context.Context, db *database.DB, uploadDir string, interval time.Duration) {
	if interval <= 0 {
		interval = 10 * time.Minute
	}

	ticker := time.NewTicker(interval)
	go func() {
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if count, err := RunCleanupPass(ctx, db, uploadDir); err != nil {
					log.Printf("[CleanupWorker] error during cleanup pass: %v", err)
				} else if count > 0 {
					log.Printf("[CleanupWorker] cleaned up %d expired items/tombstones", count)
				}
			}
		}
	}()
}
