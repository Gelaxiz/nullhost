package handlers

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"nullhost/internal/storage"
)

// HandleAPIVersion handles /api/version and /api/mobile/version
func (s *Server) HandleAPIVersion(w http.ResponseWriter, r *http.Request) {
	s.JSON(w, http.StatusOK, map[string]any{
		"status":      "ok",
		"version":     "1.2.0",
		"min_version": "1.0.0",
	})
}

// HandleCheckID handles /api/check_id and /api/check_id/{check_id}
func (s *Server) HandleCheckID(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "check_id")
	if id == "" {
		id = r.URL.Query().Get("id")
	}
	id = strings.TrimSpace(id)

	if id == "" {
		s.JSON(w, http.StatusOK, map[string]any{
			"available": false,
			"status":    "empty",
			"message":   "Custom link cannot be empty.",
		})
		return
	}

	status := s.DB.CheckIDStatus(r.Context(), id)
	switch status {
	case "available":
		s.JSON(w, http.StatusOK, map[string]any{
			"available": true,
			"status":    "available",
			"message":   "This link is available!",
		})
	case "in_use":
		s.JSON(w, http.StatusOK, map[string]any{
			"available": false,
			"status":    "in_use",
			"message":   "This link is already in use.",
		})
	case "retired":
		s.JSON(w, http.StatusOK, map[string]any{
			"available": false,
			"status":    "retired",
			"message":   "This link was previously used and permanently retired.",
		})
	case "reserved":
		s.JSON(w, http.StatusOK, map[string]any{
			"available": false,
			"status":    "reserved",
			"message":   "This link name is reserved.",
		})
	default:
		s.JSON(w, http.StatusOK, map[string]any{
			"available": false,
			"status":    "invalid",
			"message":   "Custom link must be alphanumeric and max 32 chars.",
		})
	}
}

// HandleMobileAuth handles /api/mobile/auth
func (s *Server) HandleMobileAuth(w http.ResponseWriter, r *http.Request) {
	_ = r.ParseForm()
	client := strings.TrimSpace(r.FormValue("client"))
	timestampStr := strings.TrimSpace(r.FormValue("timestamp"))
	nonce := strings.TrimSpace(r.FormValue("nonce"))
	signature := strings.TrimSpace(r.FormValue("signature"))

	validClients := map[string]bool{
		"symbian-belle-fp2":  true,
		"symbian-s60-keypad": true,
		"j2me-universal":     true,
		"windows-desktop":    true,
	}
	if !validClients[client] {
		s.JSON(w, http.StatusBadRequest, map[string]any{
			"status":  "error",
			"message": "Unknown client platform",
		})
		return
	}

	ts, err := strconv.ParseInt(timestampStr, 10, 64)
	if err != nil {
		s.JSON(w, http.StatusBadRequest, map[string]any{
			"status":  "error",
			"message": "Invalid timestamp",
		})
		return
	}

	now := time.Now().Unix()
	if math.Abs(float64(now-ts)) > 300 {
		s.JSON(w, http.StatusUnauthorized, map[string]any{
			"status":  "error",
			"message": "Timestamp out of range",
		})
		return
	}

	if len(nonce) < 4 {
		s.JSON(w, http.StatusBadRequest, map[string]any{
			"status":  "error",
			"message": "Invalid nonce",
		})
		return
	}

	// Verify HMAC
	expectedMsg := fmt.Sprintf("%s:%s:%s", client, timestampStr, nonce)
	h := hmac.New(sha256.New, []byte(s.Config.MobileAppKey))
	h.Write([]byte(expectedMsg))
	expectedSig := fmt.Sprintf("%x", h.Sum(nil))

	if !hmac.Equal([]byte(signature), []byte(expectedSig)) {
		s.JSON(w, http.StatusUnauthorized, map[string]any{
			"status":  "error",
			"message": "Invalid app credentials",
		})
		return
	}

	// Generate upload token
	randBytes := make([]byte, 8)
	_, _ = rand.Read(randBytes)
	randHex := hex.EncodeToString(randBytes)
	tokenPayload := fmt.Sprintf("%s:%d:%s", client, now, randHex)

	th := hmac.New(sha256.New, []byte(s.Config.MobileAppKey))
	th.Write([]byte(tokenPayload))
	tokenSig := fmt.Sprintf("%x", th.Sum(nil))
	uploadToken := fmt.Sprintf("%s:%s", tokenPayload, tokenSig)

	s.JSON(w, http.StatusOK, map[string]any{
		"status": "ok",
		"token":  uploadToken,
	})
}

// HandleMobileItems handles /api/mobile/items
func (s *Server) HandleMobileItems(w http.ResponseWriter, r *http.Request) {
	accountID := strings.TrimSpace(r.Header.Get("X-Nullhost-Account"))
	if accountID == "" {
		accountID = strings.TrimSpace(r.URL.Query().Get("account_id"))
	}

	if accountID == "" {
		s.JSON(w, http.StatusOK, map[string]any{"status": "ok", "items": []any{}})
		return
	}

	rows, err := s.DB.Conn().QueryContext(r.Context(), `
		SELECT id, type, COALESCE(filename, ''), size, created_at, expires_at, views, COALESCE(password_hash, '')
		FROM items
		WHERE account_id = ?
		ORDER BY created_at DESC
		LIMIT 50`, accountID)
	if err != nil {
		s.JSON(w, http.StatusInternalServerError, map[string]any{"status": "error", "message": err.Error()})
		return
	}
	defer rows.Close()

	type ItemResponse struct {
		ID          string `json:"id"`
		Filename    string `json:"filename"`
		Size        int64  `json:"size"`
		Type        string `json:"type"`
		Views       int64  `json:"views"`
		CreatedAt   int64  `json:"created_at"`
		ExpiresAt   int64  `json:"expires_at"`
		HasPassword bool   `json:"has_password"`
		URL         string `json:"url"`
		RawURL      string `json:"raw_url"`
		DownloadURL string `json:"download_url"`
	}

	var items []ItemResponse
	baseURL := s.Config.BaseURL

	for rows.Next() {
		var it ItemResponse
		var pHash string
		if err := rows.Scan(&it.ID, &it.Type, &it.Filename, &it.Size, &it.CreatedAt, &it.ExpiresAt, &it.Views, &pHash); err == nil {
			if it.Filename == "" {
				if it.Type == "paste" {
					it.Filename = "paste.txt"
				} else {
					it.Filename = "file"
				}
			}
			it.HasPassword = pHash != ""
			it.URL = fmt.Sprintf("%s/v/%s", baseURL, it.ID)
			it.RawURL = fmt.Sprintf("%s/raw/%s/%s", baseURL, it.ID, it.Filename)
			it.DownloadURL = fmt.Sprintf("%s/download/%s/%s", baseURL, it.ID, it.Filename)
			items = append(items, it)
		}
	}

	s.JSON(w, http.StatusOK, map[string]any{
		"status": "ok",
		"items":  items,
	})
}

// HandleMobileDelete handles /api/mobile/delete
func (s *Server) HandleMobileDelete(w http.ResponseWriter, r *http.Request) {
	_ = r.ParseForm()
	itemID := strings.TrimSpace(r.FormValue("id"))
	if itemID == "" {
		itemID = strings.TrimSpace(r.FormValue("item_id"))
	}
	if itemID == "" {
		s.JSON(w, http.StatusBadRequest, map[string]any{"status": "error", "message": "Missing item ID"})
		return
	}

	accountID := strings.TrimSpace(r.Header.Get("X-Nullhost-Account"))
	if accountID == "" {
		accountID = strings.TrimSpace(r.FormValue("account_id"))
	}
	deleteToken := strings.TrimSpace(r.Header.Get("X-Nullhost-Delete-Token"))
	if deleteToken == "" {
		deleteToken = strings.TrimSpace(r.FormValue("delete_token"))
	}

	item, err := s.DB.GetItem(r.Context(), itemID)
	if err != nil || item == nil {
		s.JSON(w, http.StatusNotFound, map[string]any{"status": "error", "message": "Item not found"})
		return
	}

	authorized := false
	if item.AccountID != "" {
		if accountID != "" && item.AccountID == accountID {
			authorized = true
		}
	} else if deleteToken != "" && item.DeleteTokenHash != "" {
		h := sha256.Sum256([]byte(deleteToken))
		tokenHash := fmt.Sprintf("%x", h)
		if hmac.Equal([]byte(tokenHash), []byte(item.DeleteTokenHash)) {
			authorized = true
		}
	}

	if !authorized {
		s.JSON(w, http.StatusForbidden, map[string]any{"status": "error", "message": "Unauthorized"})
		return
	}

	if item.Type == "file" && item.Filename != "" {
		fPath := storage.FilePath(s.UploadDir, item.ID, item.Filename)
		_ = storage.DeleteFile(fPath)
	}

	if err := s.DB.DeleteItem(r.Context(), itemID); err != nil {
		s.JSON(w, http.StatusInternalServerError, map[string]any{"status": "error", "message": "Failed to delete item"})
		return
	}

	s.JSON(w, http.StatusOK, map[string]any{"status": "ok", "message": "Item deleted"})
}

// HandleClientUpdate handles /api/client/update
func (s *Server) HandleClientUpdate(w http.ResponseWriter, r *http.Request) {
	baseURL := s.Config.BaseURL
	s.JSON(w, http.StatusOK, map[string]any{
		"version":      "1.2.0",
		"download_url": fmt.Sprintf("%s/download-app/windows", baseURL),
		"j2me_url":     fmt.Sprintf("%s/download-app/j2me", baseURL),
		"jad_url":      fmt.Sprintf("%s/download-app/jad", baseURL),
		"windows_url":  fmt.Sprintf("%s/download-app/windows", baseURL),
	})
}

// HandleMobilePublic handles GET /api/mobile/public
func (s *Server) HandleMobilePublic(w http.ResponseWriter, r *http.Request) {
	now := time.Now().Unix()
	rows, err := s.DB.Conn().QueryContext(r.Context(),
		"SELECT id, type, COALESCE(filename, ''), size, created_at, expires_at FROM items WHERE is_public = 1 AND expires_at > ? ORDER BY created_at DESC LIMIT 30",
		now)
	if err != nil {
		s.JSON(w, http.StatusOK, map[string]any{"status": "ok", "items": []any{}})
		return
	}
	defer rows.Close()

	type PublicItem struct {
		ID          string `json:"id"`
		Filename    string `json:"filename"`
		Size        int64  `json:"size"`
		Type        string `json:"type"`
		CreatedAt   int64  `json:"created_at"`
		ExpiresAt   int64  `json:"expires_at"`
		URL         string `json:"url"`
		RawURL      string `json:"raw_url"`
		DownloadURL string `json:"download_url"`
		QRURL       string `json:"qr_url"`
	}

	baseURL := s.Config.BaseURL
	var items []PublicItem
	for rows.Next() {
		var it PublicItem
		if err := rows.Scan(&it.ID, &it.Type, &it.Filename, &it.Size, &it.CreatedAt, &it.ExpiresAt); err == nil {
			if it.Filename == "" {
				if it.Type == "paste" {
					it.Filename = "paste.txt"
				} else {
					it.Filename = "file"
				}
			}
			it.URL = fmt.Sprintf("%s/v/%s", baseURL, it.ID)
			it.RawURL = fmt.Sprintf("%s/raw/%s/%s", baseURL, it.ID, it.Filename)
			it.DownloadURL = fmt.Sprintf("%s/download/%s/%s", baseURL, it.ID, it.Filename)
			it.QRURL = fmt.Sprintf("%s/v/%s/qr.png?size=5", baseURL, it.ID)
			items = append(items, it)
		}
	}

	s.JSON(w, http.StatusOK, map[string]any{"status": "ok", "items": items})
}

// HandleMobileItemLookup handles GET /api/mobile/item/{id}
func (s *Server) HandleMobileItemLookup(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	item, err := s.DB.GetItem(r.Context(), id)
	if err != nil || item == nil {
		s.JSON(w, http.StatusNotFound, map[string]any{"status": "error", "message": "Item not found"})
		return
	}

	baseURL := s.Config.BaseURL
	fname := item.Filename
	if fname == "" {
		if item.Type == "paste" {
			fname = "paste.txt"
		} else {
			fname = "file"
		}
	}

	s.JSON(w, http.StatusOK, map[string]any{
		"status":          "ok",
		"id":              item.ID,
		"filename":        fname,
		"size":            item.Size,
		"type":            item.Type,
		"views":           item.Views,
		"expires_at":      item.ExpiresAt,
		"has_password":    item.PasswordHash != "",
		"burn_after_read": item.BurnAfterRead,
		"content":         item.Content,
		"url":             fmt.Sprintf("%s/v/%s", baseURL, item.ID),
		"raw_url":         fmt.Sprintf("%s/raw/%s/%s", baseURL, item.ID, fname),
		"download_url":    fmt.Sprintf("%s/download/%s/%s", baseURL, item.ID, fname),
		"preview_url":     fmt.Sprintf("%s/api/mobile/preview/%s", baseURL, item.ID),
		"qr_url":          fmt.Sprintf("%s/v/%s/qr.png?size=5", baseURL, item.ID),
	})
}

// HandleMobilePreview handles GET /api/mobile/preview/{id}
func (s *Server) HandleMobilePreview(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	item, err := s.DB.GetItem(r.Context(), id)
	if err != nil || item == nil || item.ExpiresAt <= time.Now().Unix() {
		http.Error(w, "Not found or expired", http.StatusNotFound)
		return
	}

	if item.Type != "file" || item.Filename == "" {
		http.Error(w, "Preview unsupported for non-file items", http.StatusUnsupportedMediaType)
		return
	}

	filePath := storage.FilePath(s.UploadDir, item.ID, item.Filename)
	if !storage.FileExists(filePath) {
		http.Error(w, "File not found on disk", http.StatusNotFound)
		return
	}

	w.Header().Set("Cache-Control", "public, max-age=86400")
	http.ServeFile(w, r, filePath)
}
