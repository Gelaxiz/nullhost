package database

import (
	"context"
	"crypto/rand"
	"database/sql"
	"errors"
	"fmt"
	"math/big"
	"regexp"
	"strings"
	"time"

	_ "modernc.org/sqlite"
	"nullhost/internal/models"
)

var (
	ErrIDInUse    = errors.New("id is already in use")
	ErrIDRetired  = errors.New("id has been permanently retired")
	ErrIDReserved = errors.New("id is reserved for system use")
	ErrIDInvalid  = errors.New("id contains invalid characters")
	ErrNotFound   = errors.New("not found")
	customIDRegex = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,64}$`)
)

var reservedPaths = map[string]bool{
	"admin": true, "admin_dashboard": true, "upload": true, "v": true, "raw": true,
	"dl": true, "download": true, "api": true, "a": true, "static": true, "dmca": true,
	"tos": true, "privacy": true, "search": true, "favicon.ico": true, "c": true,
	"collections": true, "download-app": true, "app": true, "lite": true, "status": true,
	"view": true, "robots.txt": true, "paste": true, "login": true, "logout": true,
}

type DB struct {
	conn *sql.DB
}

func OpenDB(dbPath string) (*DB, error) {
	conn, err := sql.Open("sqlite", dbPath)
	if err != nil {
		return nil, fmt.Errorf("failed to open sqlite database: %w", err)
	}

	conn.SetMaxOpenConns(1) // SQLite single writer recommendation with WAL
	conn.SetMaxIdleConns(1)

	db := &DB{conn: conn}
	if err := db.initPragmasAndSchema(); err != nil {
		conn.Close()
		return nil, err
	}

	return db, nil
}

func (db *DB) Close() error {
	return db.conn.Close()
}

func (db *DB) Conn() *sql.DB {
	return db.conn
}

func (db *DB) initPragmasAndSchema() error {
	pragmas := []string{
		"PRAGMA journal_mode = WAL;",
		"PRAGMA synchronous = NORMAL;",
		"PRAGMA busy_timeout = 5000;",
		"PRAGMA foreign_keys = ON;",
		"PRAGMA cache_size = -4000;",
		"PRAGMA temp_store = MEMORY;",
	}

	for _, p := range pragmas {
		if _, err := db.conn.Exec(p); err != nil {
			return fmt.Errorf("error setting pragma %q: %w", p, err)
		}
	}

	schema := `
	CREATE TABLE IF NOT EXISTS accounts (
		account_id TEXT PRIMARY KEY,
		created_at INTEGER
	);

	CREATE TABLE IF NOT EXISTS items (
		id TEXT PRIMARY KEY,
		type TEXT NOT NULL,
		account_id TEXT,
		filename TEXT,
		content TEXT,
		password_hash TEXT,
		burn_after_read INTEGER DEFAULT 0,
		expires_at INTEGER NOT NULL,
		views INTEGER DEFAULT 0,
		size INTEGER,
		mimetype TEXT,
		created_at INTEGER NOT NULL,
		is_public INTEGER DEFAULT 0,
		dynamic_expiry INTEGER NOT NULL DEFAULT 0,
		is_partial INTEGER NOT NULL DEFAULT 0,
		delete_token_hash TEXT,
		FOREIGN KEY (account_id) REFERENCES accounts(account_id)
	);

	CREATE TABLE IF NOT EXISTS collections (
		id TEXT PRIMARY KEY,
		account_id TEXT NOT NULL,
		name TEXT NOT NULL,
		password_hash TEXT,
		created_at INTEGER NOT NULL,
		FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
	);

	CREATE TABLE IF NOT EXISTS collection_items (
		collection_id TEXT NOT NULL,
		item_id TEXT NOT NULL,
		folder_path TEXT NOT NULL DEFAULT '',
		added_at INTEGER NOT NULL,
		display_name TEXT,
		PRIMARY KEY (collection_id, item_id),
		FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE,
		FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE
	);

	CREATE TABLE IF NOT EXISTS tombstones (
		id TEXT PRIMARY KEY,
		reason TEXT,
		deleted_at INTEGER,
		expires_at INTEGER
	);

	CREATE TABLE IF NOT EXISTS auth_nonces (
		nonce TEXT PRIMARY KEY,
		expires_at INTEGER NOT NULL
	);

	CREATE TABLE IF NOT EXISTS retired_ids (
		id TEXT PRIMARY KEY
	);

	CREATE INDEX IF NOT EXISTS idx_collections_account ON collections(account_id);
	CREATE INDEX IF NOT EXISTS idx_collection_items_item ON collection_items(item_id);
	CREATE INDEX IF NOT EXISTS idx_items_expires ON items(expires_at);
	CREATE INDEX IF NOT EXISTS idx_items_account_created ON items(account_id, created_at);
	CREATE INDEX IF NOT EXISTS idx_tombstones_expires ON tombstones(expires_at);
	`

	if _, err := db.conn.Exec(schema); err != nil {
		return fmt.Errorf("failed to init database schema: %w", err)
	}

	return nil
}

// RetireID permanently marks an ID as retired (zero file metadata, ID only)
func (db *DB) RetireID(ctx context.Context, id string) error {
	cleanID := strings.TrimSpace(id)
	if cleanID == "" {
		return nil
	}
	_, err := db.conn.ExecContext(ctx, "INSERT OR IGNORE INTO retired_ids (id) VALUES (?)", cleanID)
	return err
}

// RetireIDs permanently marks multiple IDs as retired in one transaction
func (db *DB) RetireIDs(ctx context.Context, ids []string) error {
	if len(ids) == 0 {
		return nil
	}
	tx, err := db.conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	stmt, err := tx.PrepareContext(ctx, "INSERT OR IGNORE INTO retired_ids (id) VALUES (?)")
	if err != nil {
		return err
	}
	defer stmt.Close()

	for _, id := range ids {
		cleanID := strings.TrimSpace(id)
		if cleanID != "" {
			if _, err := stmt.ExecContext(ctx, cleanID); err != nil {
				return err
			}
		}
	}

	return tx.Commit()
}

// IsRetired checks if an ID has ever been permanently retired
func (db *DB) IsRetired(ctx context.Context, id string) (bool, error) {
	var count int
	err := db.conn.QueryRowContext(ctx, "SELECT COUNT(*) FROM retired_ids WHERE id = ?", strings.TrimSpace(id)).Scan(&count)
	if err != nil {
		return false, err
	}
	return count > 0, nil
}

// ItemOrCollectionExists checks if an ID is active in items or collections
func (db *DB) ItemOrCollectionExists(ctx context.Context, id string) (bool, error) {
	cleanID := strings.TrimSpace(id)
	var count int
	err := db.conn.QueryRowContext(ctx, "SELECT (SELECT COUNT(*) FROM items WHERE id = ?) + (SELECT COUNT(*) FROM collections WHERE id = ?)", cleanID, cleanID).Scan(&count)
	if err != nil {
		return false, err
	}
	return count > 0, nil
}

// CheckIDStatus returns exact status string for UI validation
func (db *DB) CheckIDStatus(ctx context.Context, id string) string {
	cleanID := strings.TrimSpace(id)
	if cleanID == "" || !customIDRegex.MatchString(cleanID) {
		return "invalid"
	}
	if reservedPaths[strings.ToLower(cleanID)] {
		return "reserved"
	}
	if retired, _ := db.IsRetired(ctx, cleanID); retired {
		return "retired"
	}
	if exists, _ := db.ItemOrCollectionExists(ctx, cleanID); exists {
		return "in_use"
	}
	return "available"
}

// GenerateID produces an 8-character collision-free ID that skips in-use AND retired IDs
func (db *DB) GenerateID(ctx context.Context, format string) (string, error) {
	const (
		letterChars = "abcdefghijklmnopqrstuvwxyz"
		numberChars = "0123456789"
		bothChars   = "abcdefghijklmnopqrstuvwxyz0123456789"
	)

	charset := bothChars
	switch strings.ToLower(format) {
	case "numbers":
		charset = numberChars
	case "letters":
		charset = letterChars
	}

	charsetLen := big.NewInt(int64(len(charset)))

	for attempt := 0; attempt < 50; attempt++ {
		idBytes := make([]byte, 8)
		for i := 0; i < 8; i++ {
			n, err := rand.Int(rand.Reader, charsetLen)
			if err != nil {
				return "", err
			}
			idBytes[i] = charset[n.Int64()]
		}
		id := string(idBytes)

		if reservedPaths[id] {
			continue
		}
		if retired, err := db.IsRetired(ctx, id); err != nil || retired {
			continue
		}
		if exists, err := db.ItemOrCollectionExists(ctx, id); err != nil || exists {
			continue
		}
		return id, nil
	}

	return "", errors.New("failed to generate unique non-retired id after 50 attempts")
}

// GetItem retrieves an item by ID
func (db *DB) GetItem(ctx context.Context, id string) (*models.Item, error) {
	row := db.conn.QueryRowContext(ctx, `
		SELECT id, type, COALESCE(account_id, ''), COALESCE(filename, ''),
		       COALESCE(content, ''), COALESCE(password_hash, ''),
		       burn_after_read, expires_at, views, size, mimetype,
		       created_at, is_public, dynamic_expiry, is_partial,
		       COALESCE(delete_token_hash, '')
		FROM items WHERE id = ?`, id)

	var item models.Item
	err := row.Scan(
		&item.ID, &item.Type, &item.AccountID, &item.Filename,
		&item.Content, &item.PasswordHash, &item.BurnAfterRead,
		&item.ExpiresAt, &item.Views, &item.Size, &item.MimeType,
		&item.CreatedAt, &item.IsPublic, &item.DynamicExpiry,
		&item.IsPartial, &item.DeleteTokenHash,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &item, nil
}

// InsertItem adds a new item
func (db *DB) InsertItem(ctx context.Context, item *models.Item) error {
	status := db.CheckIDStatus(ctx, item.ID)
	if status == "retired" {
		return ErrIDRetired
	}
	if status == "in_use" {
		return ErrIDInUse
	}
	if status == "reserved" {
		return ErrIDReserved
	}
	if status == "invalid" {
		return ErrIDInvalid
	}

	var accountID *string
	if item.AccountID != "" {
		accountID = &item.AccountID
	}

	if item.Type == "" {
		item.Type = "file"
	}
	if item.CreatedAt == 0 {
		item.CreatedAt = time.Now().Unix()
	}

	_, err := db.conn.ExecContext(ctx, `
		INSERT INTO items (
			id, type, account_id, filename, content, password_hash,
			burn_after_read, expires_at, views, size, mimetype,
			created_at, is_public, dynamic_expiry, is_partial, delete_token_hash
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		item.ID, item.Type, accountID, item.Filename, item.Content, item.PasswordHash,
		item.BurnAfterRead, item.ExpiresAt, item.Views, item.Size, item.MimeType,
		item.CreatedAt, item.IsPublic, item.DynamicExpiry, item.IsPartial, item.DeleteTokenHash,
	)
	return err
}

// DeleteItem removes an item and permanently retires its ID
func (db *DB) DeleteItem(ctx context.Context, id string) error {
	tx, err := db.conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(ctx, "DELETE FROM items WHERE id = ?", id); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "INSERT OR IGNORE INTO retired_ids (id) VALUES (?)", id); err != nil {
		return err
	}

	return tx.Commit()
}

// TakedownItem creates a tombstone, deletes the item, and permanently retires the ID
func (db *DB) TakedownItem(ctx context.Context, id, reason string, quarantineDays int) error {
	now := time.Now().Unix()
	expiresAt := now + int64(quarantineDays*86400)

	tx, err := db.conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(ctx, "DELETE FROM items WHERE id = ?", id); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "INSERT OR REPLACE INTO tombstones (id, reason, deleted_at, expires_at) VALUES (?, ?, ?, ?)",
		id, reason, now, expiresAt); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, "INSERT OR IGNORE INTO retired_ids (id) VALUES (?)", id); err != nil {
		return err
	}

	return tx.Commit()
}
