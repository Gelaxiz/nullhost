package handlers

import (
	"crypto/hmac"
	"crypto/sha256"
	"fmt"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"
	qrcode "github.com/skip2/go-qrcode"
	"nullhost/internal/storage"
)

// HandleSearch handles GET /search
func (s *Server) HandleSearch(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimSpace(r.URL.Query().Get("id"))
	if id == "!admin" || id == "(LolGelaxiz_1BesT!)" {
		http.Redirect(w, r, "/admin", http.StatusFound)
		return
	}
	if id != "" {
		var colID string
		err := s.DB.Conn().QueryRowContext(r.Context(), "SELECT id FROM collections WHERE id = ?", id).Scan(&colID)
		if err == nil && colID != "" {
			http.Redirect(w, r, fmt.Sprintf("/c/%s", id), http.StatusFound)
			return
		}
		http.Redirect(w, r, fmt.Sprintf("/v/%s", id), http.StatusFound)
		return
	}
	http.Redirect(w, r, "/", http.StatusFound)
}

// HandleDeleteRoute handles GET /delete/{id} and POST /delete/{id}
func (s *Server) HandleDeleteRoute(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	if id == "" {
		_ = r.ParseForm()
		id = strings.TrimSpace(r.FormValue("id"))
	}
	if id == "" {
		s.Error(w, http.StatusBadRequest, "id required")
		return
	}

	deleteToken := strings.TrimSpace(r.URL.Query().Get("token"))
	if deleteToken == "" {
		deleteToken = strings.TrimSpace(r.FormValue("token"))
	}
	if deleteToken == "" {
		deleteToken = strings.TrimSpace(r.Header.Get("X-Nullhost-Delete-Token"))
	}

	sess := s.GetSession(r)
	item, err := s.DB.GetItem(r.Context(), id)
	if err != nil || item == nil {
		s.Error(w, http.StatusNotFound, "item not found")
		return
	}

	authorized := false
	if sess.IsAdmin {
		authorized = true
	} else if item.AccountID != "" && sess.AccountID != "" && item.AccountID == sess.AccountID {
		authorized = true
	} else if deleteToken != "" && item.DeleteTokenHash != "" {
		h := sha256.Sum256([]byte(deleteToken))
		tokenHash := fmt.Sprintf("%x", h)
		if hmac.Equal([]byte(tokenHash), []byte(item.DeleteTokenHash)) {
			authorized = true
		}
	}

	if !authorized {
		s.Error(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	if item.Type == "file" && item.Filename != "" {
		_ = storage.DeleteFile(storage.FilePath(s.UploadDir, item.ID, item.Filename))
	}
	_ = s.DB.DeleteItem(r.Context(), id)

	if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") || r.Header.Get("X-Requested-With") == "XMLHttpRequest" {
		s.JSON(w, http.StatusOK, map[string]any{"status": "ok", "message": "Item deleted"})
		return
	}
	http.Redirect(w, r, "/a", http.StatusFound)
}

// HandleRenameRoute handles POST /rename/{id}
func (s *Server) HandleRenameRoute(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	_ = r.ParseForm()
	if id == "" {
		id = strings.TrimSpace(r.FormValue("id"))
	}
	newName := storage.SanitizeFilename(r.FormValue("new_name"))
	if newName == "" {
		s.Error(w, http.StatusBadRequest, "invalid filename")
		return
	}

	sess := s.GetSession(r)
	item, err := s.DB.GetItem(r.Context(), id)
	if err != nil || item == nil {
		s.Error(w, http.StatusNotFound, "item not found")
		return
	}

	authorized := false
	if sess.IsAdmin {
		authorized = true
	} else if item.AccountID != "" && sess.AccountID != "" && item.AccountID == sess.AccountID {
		authorized = true
	}

	if !authorized || item.Type != "file" {
		s.Error(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	oldPath := storage.FilePath(s.UploadDir, item.ID, item.Filename)
	newPath := storage.FilePath(s.UploadDir, item.ID, newName)
	if storage.FileExists(oldPath) {
		_ = storage.MoveFile(oldPath, newPath)
	}

	_, _ = s.DB.Conn().ExecContext(r.Context(), "UPDATE items SET filename = ? WHERE id = ?", newName, id)

	if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") || r.Header.Get("X-Requested-With") == "XMLHttpRequest" {
		s.JSON(w, http.StatusOK, map[string]any{"status": "ok", "filename": newName})
		return
	}
	http.Redirect(w, r, "/a", http.StatusFound)
}

// HandleItemsDeleteSelected handles POST /items/delete-selected and POST /api/items/delete-uploaded
func (s *Server) HandleItemsDeleteSelected(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if sess.AccountID == "" {
		if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") {
			s.JSON(w, http.StatusUnauthorized, map[string]any{"error": "Authentication required."})
			return
		}
		http.Redirect(w, r, "/a", http.StatusFound)
		return
	}

	_ = r.ParseForm()
	var selectedIDs []string

	// Check form values
	for _, it := range r.Form["item_ids"] {
		it = strings.TrimSpace(it)
		if it != "" {
			selectedIDs = append(selectedIDs, it)
		}
	}

	// Also support JSON body
	if len(selectedIDs) == 0 {
		var payload struct {
			ItemIDs       []string `json:"item_ids"`
			ConfirmDelete bool     `json:"confirm_delete"`
		}
		if err := s.ReadJSON(r, &payload); err == nil {
			selectedIDs = payload.ItemIDs
		}
	}

	if len(selectedIDs) == 0 {
		if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") {
			s.JSON(w, http.StatusBadRequest, map[string]any{"error": "At least one valid upload is required."})
			return
		}
		http.Redirect(w, r, "/a", http.StatusFound)
		return
	}

	deletedCount := 0
	for _, itID := range selectedIDs {
		item, err := s.DB.GetItem(r.Context(), itID)
		if err == nil && item != nil && (item.AccountID == sess.AccountID || sess.IsAdmin) {
			if item.Type == "file" && item.Filename != "" {
				_ = storage.DeleteFile(storage.FilePath(s.UploadDir, item.ID, item.Filename))
			}
			_ = s.DB.DeleteItem(r.Context(), itID)
			deletedCount++
		}
	}

	if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") || r.Header.Get("X-Requested-With") == "XMLHttpRequest" {
		s.JSON(w, http.StatusOK, map[string]any{"status": "ok", "deleted": deletedCount, "success": true})
		return
	}
	http.Redirect(w, r, "/a", http.StatusFound)
}

// HandleItemQR handles GET /v/{id}/qr.png
func (s *Server) HandleItemQR(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	targetURL := fmt.Sprintf("%s/v/%s", s.Config.BaseURL, id)
	pngBytes, err := qrcode.Encode(targetURL, qrcode.Medium, 256)
	if err != nil {
		s.Error(w, http.StatusInternalServerError, "failed to generate qr")
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "public, max-age=86400")
	_, _ = w.Write(pngBytes)
}
