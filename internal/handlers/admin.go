package handlers

import (
	"crypto/subtle"
	"fmt"
	"math"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/go-chi/chi/v5"
	"nullhost/internal/auth"
	"nullhost/internal/cleanup"
	"nullhost/internal/storage"
)

var (
	adminCooldowns sync.Map // map[string]int64 (ip -> last failed timestamp)
)

// HandleAdminGate handles POST/GET /admin
func (s *Server) HandleAdminGate(w http.ResponseWriter, r *http.Request) {
	ip := s.GetClientIP(r)
	now := time.Now().Unix()
	wantsJSON := r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") || r.Header.Get("X-Requested-With") == "XMLHttpRequest"

	if r.Method == http.MethodPost {
		_ = r.ParseForm()
		action := r.FormValue("action")
		if action == "logout" {
			sess := s.GetSession(r)
			sess.IsAdmin = false
			sess.AuthTime = 0
			s.SetSession(w, r, sess)
			if wantsJSON {
				s.JSON(w, http.StatusOK, map[string]any{"status": "ok", "message": "logged out"})
				return
			}
			http.Redirect(w, r, "/admin", http.StatusFound)
			return
		}

		// Check cooldown (10s)
		var cooldownRemaining int64
		if val, ok := adminCooldowns.Load(ip); ok {
			lastFailed := val.(int64)
			if now-lastFailed < 10 {
				cooldownRemaining = 10 - (now - lastFailed)
			}
		}

		if cooldownRemaining > 0 {
			if wantsJSON {
				s.JSON(w, http.StatusTooManyRequests, map[string]any{
					"status":   "error",
					"message":  fmt.Sprintf("cooldown active. please wait %d seconds...", cooldownRemaining),
					"cooldown": cooldownRemaining,
				})
				return
			}
			s.RenderHTML(w, http.StatusTooManyRequests, "admin_login.html", map[string]any{
				"password_required": true,
				"error":             fmt.Sprintf("cooldown active. please wait %d seconds...", cooldownRemaining),
				"cooldown":          cooldownRemaining,
			})
			return
		}

		password := strings.TrimSpace(r.FormValue("password"))
		if password == "" {
			password = strings.TrimSpace(r.FormValue("admin_password"))
		}

		// Support both totp_code (used in admin_login.html) and totp (API / legacy)
		totpCode := strings.TrimSpace(r.FormValue("totp_code"))
		if totpCode == "" {
			totpCode = strings.TrimSpace(r.FormValue("totp"))
		}

		// 1. Password check
		pwdValid := true
		if s.Config.AdminPassword != "" {
			pwdValid = subtle.ConstantTimeCompare([]byte(password), []byte(s.Config.AdminPassword)) == 1
		}

		// 2. TOTP check (if configured)
		totpValid := true
		if s.Config.AdminTOTPSecret != "" {
			totpValid = auth.VerifyTOTP(s.Config.AdminTOTPSecret, totpCode, 2)
		}

		if !pwdValid || !totpValid {
			adminCooldowns.Store(ip, now)
			errMsg := "incorrect 6-digit authenticator code or password."
			if !pwdValid && totpValid {
				errMsg = "incorrect security password."
			} else if pwdValid && !totpValid {
				errMsg = "incorrect 6-digit authenticator code."
			}

			if wantsJSON {
				s.JSON(w, http.StatusUnauthorized, map[string]any{
					"status":   "error",
					"message":  errMsg,
					"cooldown": 10,
				})
				return
			}

			s.RenderHTML(w, http.StatusUnauthorized, "admin_login.html", map[string]any{
				"password_required": true,
				"error":             errMsg,
				"cooldown":          10,
			})
			return
		}

		adminCooldowns.Delete(ip)
		sess := s.GetSession(r)
		sess.IsAdmin = true
		sess.AuthTime = now
		s.SetSession(w, r, sess)

		if wantsJSON {
			s.JSON(w, http.StatusOK, map[string]any{
				"status":  "ok",
				"message": "admin authenticated",
			})
			return
		}

		http.Redirect(w, r, "/admin_dashboard", http.StatusFound)
		return
	}

	// GET request: return auth status or render login page
	sess := s.GetSession(r)
	if wantsJSON {
		s.JSON(w, http.StatusOK, map[string]any{
			"is_admin": sess.IsAdmin,
		})
		return
	}
	if sess.IsAdmin {
		http.Redirect(w, r, "/admin_dashboard", http.StatusFound)
		return
	}

	var cooldownRemaining int64
	if val, ok := adminCooldowns.Load(ip); ok {
		lastFailed := val.(int64)
		if now-lastFailed < 10 {
			cooldownRemaining = 10 - (now - lastFailed)
		}
	}

	s.RenderHTML(w, http.StatusOK, "admin_login.html", map[string]any{
		"password_required": true,
		"cooldown":          cooldownRemaining,
	})
}

// HandleAdminSessionCheck handles GET /admin/session_check
func (s *Server) HandleAdminSessionCheck(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	s.JSON(w, http.StatusOK, map[string]any{
		"is_admin":  sess.IsAdmin,
		"auth_time": sess.AuthTime,
	})
}

// HandleAdminDashboard handles GET /admin_dashboard
func (s *Server) HandleAdminDashboard(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if !sess.IsAdmin {
		if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") {
			s.JSON(w, http.StatusUnauthorized, map[string]any{"error": "admin access required"})
			return
		}
		http.Redirect(w, r, "/admin", http.StatusFound)
		return
	}

	ctx := r.Context()
	now := time.Now().Unix()
	q := strings.TrimSpace(r.URL.Query().Get("q"))

	var itemsCount, filesCount, pastesCount, accsCount, tombCount int
	var totalSize int64

	_ = s.DB.Conn().QueryRowContext(ctx, "SELECT COUNT(*) FROM items").Scan(&itemsCount)
	_ = s.DB.Conn().QueryRowContext(ctx, "SELECT COUNT(*) FROM items WHERE type = 'file'").Scan(&filesCount)
	_ = s.DB.Conn().QueryRowContext(ctx, "SELECT COUNT(*) FROM items WHERE type = 'paste'").Scan(&pastesCount)
	_ = s.DB.Conn().QueryRowContext(ctx, "SELECT COUNT(*) FROM accounts").Scan(&accsCount)
	_ = s.DB.Conn().QueryRowContext(ctx, "SELECT COUNT(*) FROM tombstones WHERE expires_at > ?", now).Scan(&tombCount)
	_ = s.DB.Conn().QueryRowContext(ctx, "SELECT COALESCE(SUM(size), 0) FROM items").Scan(&totalSize)

	type DashboardItem struct {
		ID        string `json:"id"`
		Type      string `json:"type"`
		AccountID string `json:"account_id"`
		Filename  string `json:"filename"`
		Size      int64  `json:"size"`
		Views     int64  `json:"views"`
		ExpiresAt int64  `json:"expires_at"`
	}

	var items []DashboardItem
	var query string
	var args []any

	if q != "" {
		query = `
			SELECT id, type, COALESCE(account_id, ''), COALESCE(filename, ''), size, views, expires_at
			FROM items
			WHERE id LIKE ? OR filename LIKE ? OR account_id LIKE ?
			ORDER BY created_at DESC LIMIT 100`
		param := "%" + q + "%"
		args = []any{param, param, param}
	} else {
		query = `
			SELECT id, type, COALESCE(account_id, ''), COALESCE(filename, ''), size, views, expires_at
			FROM items
			ORDER BY created_at DESC LIMIT 100`
	}

	rows, err := s.DB.Conn().QueryContext(ctx, query, args...)
	if err == nil {
		for rows.Next() {
			var it DashboardItem
			if err := rows.Scan(&it.ID, &it.Type, &it.AccountID, &it.Filename, &it.Size, &it.Views, &it.ExpiresAt); err == nil {
				if it.Filename == "" {
					if it.Type == "paste" {
						it.Filename = "paste.txt"
					} else {
						it.Filename = "file"
					}
				}
				items = append(items, it)
			}
		}
		rows.Close()
	}

	totalSizeMB := float64(0)
	if totalSize > 0 {
		totalSizeMB = math.Round((float64(totalSize)/1048576.0)*100) / 100
	}

	stats := map[string]any{
		"items_count":   itemsCount,
		"files_count":   filesCount,
		"pastes_count":  pastesCount,
		"accs_count":    accsCount,
		"tomb_count":    tombCount,
		"total_size":    totalSize,
		"total_size_mb": fmt.Sprintf("%.2f", totalSizeMB),
	}

	// Fetch accounts
	type DashboardAccount struct {
		AccountID string `json:"account_id"`
		CreatedAt int64  `json:"created_at"`
		ItemCount int64  `json:"item_count"`
		TotalSize int64  `json:"total_size"`
	}
	var accounts []DashboardAccount
	accRows, err := s.DB.Conn().QueryContext(ctx, `
		SELECT a.account_id, COALESCE(a.created_at, 0), COUNT(i.id), COALESCE(SUM(i.size), 0)
		FROM accounts a
		LEFT JOIN items i ON a.account_id = i.account_id
		GROUP BY a.account_id
		ORDER BY a.created_at DESC LIMIT 50`)
	if err == nil {
		for accRows.Next() {
			var a DashboardAccount
			if err := accRows.Scan(&a.AccountID, &a.CreatedAt, &a.ItemCount, &a.TotalSize); err == nil {
				accounts = append(accounts, a)
			}
		}
		accRows.Close()
	}

	// Fetch tombstones
	type DashboardTombstone struct {
		ID        string `json:"id"`
		Reason    string `json:"reason"`
		DeletedAt int64  `json:"deleted_at"`
		ExpiresAt int64  `json:"expires_at"`
	}
	var tombstones []DashboardTombstone
	tombRows, err := s.DB.Conn().QueryContext(ctx, `
		SELECT id, COALESCE(reason, ''), COALESCE(deleted_at, 0), COALESCE(expires_at, 0)
		FROM tombstones
		ORDER BY deleted_at DESC LIMIT 50`)
	if err == nil {
		for tombRows.Next() {
			var t DashboardTombstone
			if err := tombRows.Scan(&t.ID, &t.Reason, &t.DeletedAt, &t.ExpiresAt); err == nil {
				tombstones = append(tombstones, t)
			}
		}
		tombRows.Close()
	}

	if r.URL.Query().Get("format") == "json" || r.Header.Get("Accept") == "application/json" {
		s.JSON(w, http.StatusOK, map[string]any{
			"status":     "ok",
			"stats":      stats,
			"items":      items,
			"accounts":   accounts,
			"tombstones": tombstones,
			"q":          q,
		})
		return
	}

	s.RenderHTML(w, http.StatusOK, "admin_dashboard.html", map[string]any{
		"stats":          stats,
		"items":          items,
		"accounts":       accounts,
		"tombstones":     tombstones,
		"now":            now,
		"q":              q,
		"just_logged_in": true,
		"admin_csrf":     "nullhost-admin-authenticated",
	})
}

// HandleAdminLogout handles GET and POST /admin/logout
func (s *Server) HandleAdminLogout(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	sess.IsAdmin = false
	sess.AuthTime = 0
	s.SetSession(w, r, sess)
	if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") {
		s.JSON(w, http.StatusOK, map[string]any{"status": "ok", "message": "logged out"})
		return
	}
	http.Redirect(w, r, "/admin", http.StatusFound)
}

// HandleAdminDeleteItem handles POST /admin/delete_item/{id}
func (s *Server) HandleAdminDeleteItem(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if !sess.IsAdmin {
		s.Error(w, http.StatusUnauthorized, "admin access required")
		return
	}

	id := chi.URLParam(r, "id")
	if id == "" {
		_ = r.ParseForm()
		id = strings.TrimSpace(r.FormValue("id"))
	}
	if id == "" {
		s.Error(w, http.StatusBadRequest, "id required")
		return
	}

	item, err := s.DB.GetItem(r.Context(), id)
	if err == nil && item != nil && item.Type == "file" && item.Filename != "" {
		_ = storage.DeleteFile(storage.FilePath(s.UploadDir, item.ID, item.Filename))
	}

	_ = s.DB.DeleteItem(r.Context(), id)

	if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") || r.Header.Get("X-Requested-With") == "XMLHttpRequest" {
		s.JSON(w, http.StatusOK, map[string]any{
			"status":  "ok",
			"message": fmt.Sprintf("Item %s deleted and ID permanently retired", id),
		})
		return
	}
	http.Redirect(w, r, "/admin_dashboard", http.StatusFound)
}

// HandleAdminTakedownItem handles POST /admin/takedown_item/{id}
func (s *Server) HandleAdminTakedownItem(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if !sess.IsAdmin {
		s.Error(w, http.StatusUnauthorized, "admin access required")
		return
	}

	id := chi.URLParam(r, "id")
	_ = r.ParseForm()
	if id == "" {
		id = strings.TrimSpace(r.FormValue("id"))
	}
	reason := strings.TrimSpace(r.FormValue("reason"))
	if reason == "" {
		reason = "Admin Takedown / TOS Violation"
	}

	item, err := s.DB.GetItem(r.Context(), id)
	if err == nil && item != nil && item.Type == "file" && item.Filename != "" {
		_ = storage.DeleteFile(storage.FilePath(s.UploadDir, item.ID, item.Filename))
	}

	_ = s.DB.TakedownItem(r.Context(), id, reason, 30)

	if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") || r.Header.Get("X-Requested-With") == "XMLHttpRequest" {
		s.JSON(w, http.StatusOK, map[string]any{
			"status":  "ok",
			"message": fmt.Sprintf("Item %s taken down: %s", id, reason),
		})
		return
	}
	http.Redirect(w, r, "/admin_dashboard", http.StatusFound)
}

// HandleAdminDeleteAccount handles POST /admin/delete_account/{id}
func (s *Server) HandleAdminDeleteAccount(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if !sess.IsAdmin {
		s.Error(w, http.StatusUnauthorized, "admin access required")
		return
	}

	accID := chi.URLParam(r, "id")
	if accID == "" {
		_ = r.ParseForm()
		accID = strings.TrimSpace(r.FormValue("account_id"))
	}

	// Delete physical files and retire IDs
	rows, err := s.DB.Conn().QueryContext(r.Context(), "SELECT id, filename, type FROM items WHERE account_id = ?", accID)
	if err == nil {
		for rows.Next() {
			var itID, fname, itType string
			if rows.Scan(&itID, &fname, &itType) == nil {
				if itType == "file" && fname != "" {
					_ = storage.DeleteFile(storage.FilePath(s.UploadDir, itID, fname))
				}
				_ = s.DB.RetireID(r.Context(), itID)
			}
		}
		rows.Close()
	}

	_, _ = s.DB.Conn().ExecContext(r.Context(), "DELETE FROM items WHERE account_id = ?", accID)
	_, _ = s.DB.Conn().ExecContext(r.Context(), "DELETE FROM collections WHERE account_id = ?", accID)
	_, _ = s.DB.Conn().ExecContext(r.Context(), "DELETE FROM accounts WHERE account_id = ?", accID)

	if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") || r.Header.Get("X-Requested-With") == "XMLHttpRequest" {
		s.JSON(w, http.StatusOK, map[string]any{"status": "ok", "message": "Account deleted"})
		return
	}
	http.Redirect(w, r, "/admin_dashboard", http.StatusFound)
}

// HandleAdminRevokeTombstone handles POST /admin/revoke_tombstone/{id}
func (s *Server) HandleAdminRevokeTombstone(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if !sess.IsAdmin {
		s.Error(w, http.StatusUnauthorized, "admin access required")
		return
	}

	id := chi.URLParam(r, "id")
	if id == "" {
		_ = r.ParseForm()
		id = strings.TrimSpace(r.FormValue("id"))
	}

	_, _ = s.DB.Conn().ExecContext(r.Context(), "DELETE FROM tombstones WHERE id = ?", id)

	if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") || r.Header.Get("X-Requested-With") == "XMLHttpRequest" {
		s.JSON(w, http.StatusOK, map[string]any{"status": "ok", "message": "Tombstone revoked"})
		return
	}
	http.Redirect(w, r, "/admin_dashboard", http.StatusFound)
}

// HandleAdminUpdateTombstoneReason handles POST /admin/update_tombstone_reason/{id}
func (s *Server) HandleAdminUpdateTombstoneReason(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if !sess.IsAdmin {
		s.Error(w, http.StatusUnauthorized, "admin access required")
		return
	}

	id := chi.URLParam(r, "id")
	_ = r.ParseForm()
	if id == "" {
		id = strings.TrimSpace(r.FormValue("id"))
	}
	reason := strings.TrimSpace(r.FormValue("reason"))
	if reason != "" {
		_, _ = s.DB.Conn().ExecContext(r.Context(), "UPDATE tombstones SET reason = ? WHERE id = ?", reason, id)
	}

	if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") || r.Header.Get("X-Requested-With") == "XMLHttpRequest" {
		s.JSON(w, http.StatusOK, map[string]any{"status": "ok", "message": "Tombstone updated"})
		return
	}
	http.Redirect(w, r, "/admin_dashboard", http.StatusFound)
}

// HandleAdminDelete handles POST /admin/delete
func (s *Server) HandleAdminDelete(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if !sess.IsAdmin {
		s.Error(w, http.StatusUnauthorized, "admin access required")
		return
	}

	_ = r.ParseForm()
	id := strings.TrimSpace(r.FormValue("id"))
	if id == "" {
		s.Error(w, http.StatusBadRequest, "id required")
		return
	}

	item, err := s.DB.GetItem(r.Context(), id)
	if err == nil && item != nil && item.Type == "file" && item.Filename != "" {
		_ = storage.DeleteFile(storage.FilePath(s.UploadDir, item.ID, item.Filename))
	}

	if err := s.DB.DeleteItem(r.Context(), id); err != nil {
		s.Error(w, http.StatusInternalServerError, "failed to delete item")
		return
	}

	s.JSON(w, http.StatusOK, map[string]any{
		"status":  "ok",
		"message": fmt.Sprintf("Item %s deleted and ID permanently retired", id),
	})
}

// HandleAdminTakedown handles POST /admin/takedown
func (s *Server) HandleAdminTakedown(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if !sess.IsAdmin {
		s.Error(w, http.StatusUnauthorized, "admin access required")
		return
	}

	_ = r.ParseForm()
	id := strings.TrimSpace(r.FormValue("id"))
	reason := strings.TrimSpace(r.FormValue("reason"))
	if reason == "" {
		reason = "DMCA / Legal Takedown"
	}
	days, _ := strconv.Atoi(r.FormValue("days"))
	if days <= 0 {
		days = 30
	}

	item, err := s.DB.GetItem(r.Context(), id)
	if err == nil && item != nil && item.Type == "file" && item.Filename != "" {
		_ = storage.DeleteFile(storage.FilePath(s.UploadDir, item.ID, item.Filename))
	}

	if err := s.DB.TakedownItem(r.Context(), id, reason, days); err != nil {
		s.Error(w, http.StatusInternalServerError, "failed to takedown item")
		return
	}

	s.JSON(w, http.StatusOK, map[string]any{
		"status":  "ok",
		"message": fmt.Sprintf("Item %s taken down: %s (Quarantine: %d days)", id, reason, days),
	})
}

// HandleAdminCleanup handles POST /admin/cleanup and POST /admin/run_cleanup
func (s *Server) HandleAdminCleanup(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if !sess.IsAdmin {
		s.Error(w, http.StatusUnauthorized, "admin access required")
		return
	}

	count, err := cleanup.RunCleanupPass(r.Context(), s.DB, s.UploadDir)
	if err != nil {
		s.Error(w, http.StatusInternalServerError, fmt.Sprintf("cleanup error: %v", err))
		return
	}

	if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") || r.Header.Get("X-Requested-With") == "XMLHttpRequest" {
		s.JSON(w, http.StatusOK, map[string]any{
			"status":        "ok",
			"cleaned_count": count,
		})
		return
	}
	http.Redirect(w, r, "/admin_dashboard", http.StatusFound)
}
