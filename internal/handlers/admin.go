package handlers

import (
	"crypto/subtle"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

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

	stats := map[string]any{
		"items_count":  itemsCount,
		"files_count":  filesCount,
		"pastes_count": pastesCount,
		"accs_count":   accsCount,
		"tomb_count":   tombCount,
		"total_size":   totalSize,
	}

	if r.URL.Query().Get("format") == "json" || r.Header.Get("Accept") == "application/json" {
		s.JSON(w, http.StatusOK, map[string]any{
			"status": "ok",
			"stats":  stats,
			"items":  items,
			"q":      q,
		})
		return
	}

	s.RenderHTML(w, http.StatusOK, "admin_dashboard.html", map[string]any{
		"stats": stats,
		"items": items,
		"now":   now,
		"q":     q,
	})
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

// HandleAdminCleanup handles POST /admin/cleanup
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

	s.JSON(w, http.StatusOK, map[string]any{
		"status":        "ok",
		"cleaned_count": count,
	})
}
