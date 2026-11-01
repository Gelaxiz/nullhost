package handlers

import (
	"net/http"
	"strings"
	"time"

	"nullhost/internal/auth"
	"nullhost/internal/storage"
)

// HandleAccount handles /account
func (s *Server) HandleAccount(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if sess.AccountID == "" {
		s.JSON(w, http.StatusOK, map[string]any{
			"logged_in": false,
		})
		return
	}

	var itemCount int
	var totalSize int64
	_ = s.DB.Conn().QueryRowContext(r.Context(),
		"SELECT COUNT(*), COALESCE(SUM(size), 0) FROM items WHERE account_id = ?", sess.AccountID).
		Scan(&itemCount, &totalSize)

	s.JSON(w, http.StatusOK, map[string]any{
		"logged_in":  true,
		"account_id": sess.AccountID,
		"item_count": itemCount,
		"total_size": totalSize,
	})
}

// HandleAccountCreate handles /account/create
func (s *Server) HandleAccountCreate(w http.ResponseWriter, r *http.Request) {
	digits := 16
	if r.FormValue("digits") == "24" {
		digits = 24
	}

	accID, err := auth.GenerateNumericAccountID(digits)
	if err != nil {
		s.Error(w, http.StatusInternalServerError, "failed to generate account id")
		return
	}

	now := time.Now().Unix()
	_, err = s.DB.Conn().ExecContext(r.Context(),
		"INSERT INTO accounts (account_id, created_at) VALUES (?, ?)", accID, now)
	if err != nil {
		s.Error(w, http.StatusInternalServerError, "failed to create account in database")
		return
	}

	sess := s.GetSession(r)
	sess.AccountID = accID
	s.SetSession(w, r, sess)

	s.JSON(w, http.StatusOK, map[string]any{
		"status":     "ok",
		"account_id": accID,
	})
}

// HandleAccountLogin handles /account/login
func (s *Server) HandleAccountLogin(w http.ResponseWriter, r *http.Request) {
	_ = r.ParseForm()
	accID := strings.TrimSpace(r.FormValue("account_id"))
	if accID == "" {
		s.Error(w, http.StatusBadRequest, "account_id is required")
		return
	}

	var exists int
	err := s.DB.Conn().QueryRowContext(r.Context(),
		"SELECT COUNT(*) FROM accounts WHERE account_id = ?", accID).Scan(&exists)
	if err != nil || exists == 0 {
		s.Error(w, http.StatusUnauthorized, "invalid account id")
		return
	}

	sess := s.GetSession(r)
	sess.AccountID = accID
	s.SetSession(w, r, sess)

	s.JSON(w, http.StatusOK, map[string]any{
		"status":     "ok",
		"account_id": accID,
	})
}

// HandleAccountLogout handles /account/logout
func (s *Server) HandleAccountLogout(w http.ResponseWriter, r *http.Request) {
	s.ClearSession(w)
	s.JSON(w, http.StatusOK, map[string]any{
		"status": "ok",
	})
}

// HandleAccountNuke handles /account/nuke
func (s *Server) HandleAccountNuke(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if sess.AccountID == "" {
		s.Error(w, http.StatusUnauthorized, "not logged in")
		return
	}

	_ = r.ParseForm()
	if strings.TrimSpace(r.FormValue("confirmation")) != "NUKE" {
		s.Error(w, http.StatusBadRequest, "confirmation must be exactly NUKE")
		return
	}

	accID := sess.AccountID
	ctx := r.Context()

	// 1. Fetch all items owned by account to delete physical files and retire IDs
	rows, err := s.DB.Conn().QueryContext(ctx, "SELECT id, filename, type FROM items WHERE account_id = ?", accID)
	if err != nil {
		s.Error(w, http.StatusInternalServerError, "database error")
		return
	}

	type ItemToDelete struct {
		ID       string
		Filename string
		Type     string
	}
	var items []ItemToDelete
	var itemIDs []string

	for rows.Next() {
		var it ItemToDelete
		if err := rows.Scan(&it.ID, &it.Filename, &it.Type); err == nil {
			items = append(items, it)
			itemIDs = append(itemIDs, it.ID)
		}
	}
	rows.Close()

	for _, it := range items {
		if it.Type == "file" && it.Filename != "" {
			_ = storage.DeleteFile(storage.FilePath(s.UploadDir, it.ID, it.Filename))
		}
	}

	// 2. Fetch all collections owned by account to retire their IDs
	colRows, err := s.DB.Conn().QueryContext(ctx, "SELECT id FROM collections WHERE account_id = ?", accID)
	var colIDs []string
	if err == nil {
		for colRows.Next() {
			var cid string
			if err := colRows.Scan(&cid); err == nil {
				colIDs = append(colIDs, cid)
			}
		}
		colRows.Close()
	}

	// 3. Retire all IDs permanently
	allIDsToRetire := append(itemIDs, colIDs...)
	_ = s.DB.RetireIDs(ctx, allIDsToRetire)

	// 4. Delete items, collections, accounts in transaction
	tx, err := s.DB.Conn().BeginTx(ctx, nil)
	if err != nil {
		s.Error(w, http.StatusInternalServerError, "transaction failed")
		return
	}
	defer tx.Rollback()

	_, _ = tx.ExecContext(ctx, "DELETE FROM items WHERE account_id = ?", accID)
	_, _ = tx.ExecContext(ctx, "DELETE FROM collections WHERE account_id = ?", accID)
	_, _ = tx.ExecContext(ctx, "DELETE FROM accounts WHERE account_id = ?", accID)

	if err := tx.Commit(); err != nil {
		s.Error(w, http.StatusInternalServerError, "commit failed")
		return
	}

	s.ClearSession(w)
	s.JSON(w, http.StatusOK, map[string]any{
		"status":  "ok",
		"message": "account and all associated items and collections permanently destroyed",
	})
}
