package handlers

import (
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"nullhost/internal/auth"
	"nullhost/internal/storage"
)

// HandleRawAndDownload handles file retrieval, inline view, or forced download
func (s *Server) HandleRawAndDownload(w http.ResponseWriter, r *http.Request, forceDownload bool) {
	itemID := chi.URLParam(r, "id")
	if itemID == "" {
		itemID = chi.URLParam(r, "item_id")
	}
	filename := chi.URLParam(r, "filename")
	now := time.Now().Unix()

	// 1. Check tombstones (451 Unavailable For Legal Reasons)
	var reason string
	var tombExpires int64
	err := s.DB.Conn().QueryRowContext(r.Context(),
		"SELECT reason, expires_at FROM tombstones WHERE id = ? AND expires_at > ?", itemID, now).
		Scan(&reason, &tombExpires)
	if err == nil {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(http.StatusUnavailableForLegalReasons)
		fmt.Fprintf(w, "Unavailable For Legal Reasons: %s (Expires: %d)\n", reason, tombExpires)
		return
	}

	// 2. Fetch item from database
	item, err := s.DB.GetItem(r.Context(), itemID)
	if err != nil || item == nil {
		// Check if it is a collection ID
		var colID string
		_ = s.DB.Conn().QueryRowContext(r.Context(), "SELECT id FROM collections WHERE id = ?", itemID).Scan(&colID)
		if colID != "" {
			http.Redirect(w, r, fmt.Sprintf("/c/%s/download.zip", itemID), http.StatusFound)
			return
		}

		// Check if permanently retired
		isRetired, _ := s.DB.IsRetired(r.Context(), itemID)
		if isRetired {
			s.Error(w, http.StatusNotFound, "error: this link was previously used and permanently retired")
			return
		}

		s.Error(w, http.StatusNotFound, "error: not found or expired")
		return
	}

	// 3. Expiration check
	if item.ExpiresAt <= now {
		if item.Type == "file" && item.Filename != "" {
			_ = storage.DeleteFile(storage.FilePath(s.UploadDir, item.ID, item.Filename))
		}
		_ = s.DB.DeleteItem(r.Context(), itemID)
		s.Error(w, http.StatusNotFound, "error: this link was previously used and permanently retired")
		return
	}

	// 4. Password validation
	if item.PasswordHash != "" {
		pwd := r.URL.Query().Get("password")
		if pwd == "" {
			pwd = r.Header.Get("X-Nullhost-Password")
		}
		if pwd == "" {
			pwd = r.FormValue("password")
		}

		valid, _ := auth.VerifyPassword(pwd, item.PasswordHash)
		if !valid {
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			w.WriteHeader(http.StatusUnauthorized)
			fmt.Fprintln(w, `{"error": "password required or invalid"}`)
			return
		}
	}

	// 5. Dynamic expiry calculation
	newExpires := item.ExpiresAt
	if item.DynamicExpiry == 1 && item.ExpiresAt <= 2000000000 {
		extensionCap := item.CreatedAt + (180 * 86400)
		if item.ExpiresAt < extensionCap {
			extended := item.ExpiresAt + (3 * 86400)
			if extended > extensionCap {
				newExpires = extensionCap
			} else {
				newExpires = extended
			}
		}
	}

	// 6. Burn-after-read & views tracking
	burnTriggered := false
	if item.BurnAfterRead > 0 {
		res, err := s.DB.Conn().ExecContext(r.Context(), `
			UPDATE items
			SET views = views + 1, expires_at = ?
			WHERE id = ? AND views < burn_after_read`,
			newExpires, itemID)
		if err != nil {
			s.Error(w, http.StatusInternalServerError, "view count error")
			return
		}
		affected, _ := res.RowsAffected()
		if affected == 0 {
			s.Error(w, http.StatusNotFound, "error: not found or expired")
			return
		}

		var views, burnLimit int64
		_ = s.DB.Conn().QueryRowContext(r.Context(), "SELECT views, burn_after_read FROM items WHERE id = ?", itemID).
			Scan(&views, &burnLimit)
		if views >= burnLimit {
			burnTriggered = true
			_ = s.DB.DeleteItem(r.Context(), itemID)
		}
	} else {
		_, _ = s.DB.Conn().ExecContext(r.Context(), `
			UPDATE items
			SET views = views + 1, expires_at = ?
			WHERE id = ?`, newExpires, itemID)
	}

	// 7. Deliver Content
	if item.Type == "file" {
		actualName := item.Filename
		if filename != "" {
			actualName = filename
		}
		diskPath := storage.FilePath(s.UploadDir, item.ID, item.Filename)

		f, err := os.Open(diskPath)
		if err != nil {
			s.Error(w, http.StatusNotFound, "error: file not found on disk")
			return
		}
		defer f.Close()

		fi, err := f.Stat()
		if err != nil {
			s.Error(w, http.StatusInternalServerError, "stat failed")
			return
		}

		mime := item.MimeType
		if mime == "" {
			mime = "application/octet-stream"
		}
		mimetypeClean := strings.ToLower(strings.Split(mime, ";")[0])

		asAttachment := forceDownload || r.URL.Query().Get("dl") == "1"
		activeTypes := map[string]bool{
			"text/html":             true,
			"application/xhtml+xml": true,
			"image/svg+xml":         true,
			"text/xml":              true,
			"application/xml":       true,
		}
		if activeTypes[mimetypeClean] {
			asAttachment = true
		}

		if asAttachment {
			w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, storage.SanitizeFilename(actualName)))
		} else {
			w.Header().Set("Content-Disposition", fmt.Sprintf(`inline; filename="%s"`, storage.SanitizeFilename(actualName)))
		}

		if strings.HasPrefix(mimetypeClean, "video/") || strings.HasPrefix(mimetypeClean, "audio/") || strings.HasPrefix(mimetypeClean, "image/") {
			w.Header().Set("Content-Security-Policy", "default-src 'none'; media-src 'self' blob:; img-src 'self' data: blob:; style-src 'unsafe-inline'")
			w.Header().Set("Accept-Ranges", "bytes")
		} else {
			w.Header().Set("Content-Security-Policy", "sandbox; default-src 'none'; frame-ancestors 'none'")
		}

		w.Header().Set("Content-Type", mime)
		http.ServeContent(w, r, actualName, fi.ModTime(), f)

		if burnTriggered {
			_ = storage.DeleteFile(diskPath)
		}
		return
	}

	// Paste item delivery
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	if forceDownload || r.URL.Query().Get("dl") == "1" {
		w.Header().Set("Content-Disposition", `attachment; filename="paste.txt"`)
	}
	_, _ = w.Write([]byte(item.Content))
}

// HandleOverview handles /v/{id}
func (s *Server) HandleOverview(w http.ResponseWriter, r *http.Request) {
	itemID := chi.URLParam(r, "id")
	item, err := s.DB.GetItem(r.Context(), itemID)
	if err != nil || item == nil {
		isRetired, _ := s.DB.IsRetired(r.Context(), itemID)
		if isRetired {
			s.Error(w, http.StatusNotFound, "error: this link was previously used and permanently retired")
			return
		}
		s.Error(w, http.StatusNotFound, "error: not found")
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

	res := map[string]any{
		"id":           item.ID,
		"filename":     fname,
		"type":         item.Type,
		"size":         item.Size,
		"views":        item.Views,
		"created_at":   item.CreatedAt,
		"expires_at":   item.ExpiresAt,
		"is_public":    item.IsPublic,
		"has_password": item.PasswordHash != "",
		"raw_url":      fmt.Sprintf("%s/raw/%s/%s", baseURL, item.ID, fname),
		"download_url": fmt.Sprintf("%s/download/%s/%s", baseURL, item.ID, fname),
	}

	if r.URL.Query().Get("format") == "json" || r.Header.Get("Accept") == "application/json" {
		s.JSON(w, http.StatusOK, res)
		return
	}

	s.RenderHTML(w, http.StatusOK, "overview.html", map[string]any{
		"item":    res,
		"item_id": item.ID,
	})
}

// HandleCollectionZip handles streaming ZIP download: /c/{id}/download.zip
func (s *Server) HandleCollectionZip(w http.ResponseWriter, r *http.Request) {
	colID := chi.URLParam(r, "id")
	var colName, pHash string
	err := s.DB.Conn().QueryRowContext(r.Context(),
		"SELECT name, COALESCE(password_hash, '') FROM collections WHERE id = ?", colID).
		Scan(&colName, &pHash)
	if err != nil {
		s.Error(w, http.StatusNotFound, "collection not found")
		return
	}

	// Verify collection password if set
	if pHash != "" {
		pwd := r.URL.Query().Get("password")
		if pwd == "" {
			pwd = r.Header.Get("X-Nullhost-Password")
		}
		valid, _ := auth.VerifyPassword(pwd, pHash)
		if !valid {
			s.Error(w, http.StatusUnauthorized, "collection password required")
			return
		}
	}

	rows, err := s.DB.Conn().QueryContext(r.Context(), `
		SELECT i.id, COALESCE(i.filename, ''), COALESCE(ci.folder_path, ''), i.type, COALESCE(i.content, '')
		FROM collection_items ci
		JOIN items i ON ci.item_id = i.id
		WHERE ci.collection_id = ?`, colID)
	if err != nil {
		s.Error(w, http.StatusInternalServerError, "failed to read collection items")
		return
	}
	defer rows.Close()

	var entries []storage.CollectionZipEntry
	for rows.Next() {
		var e storage.CollectionZipEntry
		if err := rows.Scan(&e.ItemID, &e.Filename, &e.FolderPath, &e.Type, &e.Content); err == nil {
			if e.Filename == "" {
				if e.Type == "paste" {
					e.Filename = "paste.txt"
				} else {
					e.Filename = "file"
				}
			}
			entries = append(entries, e)
		}
	}

	zipName := storage.SanitizeFilename(colName) + ".zip"
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, zipName))

	if err := storage.StreamZipCollection(w, s.UploadDir, entries); err != nil {
		// Log or handle
		return
	}
}
