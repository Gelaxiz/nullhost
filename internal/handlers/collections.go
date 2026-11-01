package handlers

import (
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	qrcode "github.com/skip2/go-qrcode"
	"nullhost/internal/auth"
	"nullhost/internal/storage"
)

// HandleCollectionsList handles GET /collections
func (s *Server) HandleCollectionsList(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if sess.AccountID == "" {
		s.Error(w, http.StatusUnauthorized, "not logged in")
		return
	}

	rows, err := s.DB.Conn().QueryContext(r.Context(),
		"SELECT id, name, created_at, password_hash FROM collections WHERE account_id = ? ORDER BY created_at DESC", sess.AccountID)
	if err != nil {
		s.Error(w, http.StatusInternalServerError, "database error")
		return
	}
	defer rows.Close()

	type ColSummary struct {
		ID          string `json:"id"`
		Name        string `json:"name"`
		CreatedAt   int64  `json:"created_at"`
		HasPassword bool   `json:"has_password"`
	}

	var collections []ColSummary
	for rows.Next() {
		var c ColSummary
		var pHash string
		if err := rows.Scan(&c.ID, &c.Name, &c.CreatedAt, &pHash); err == nil {
			c.HasPassword = pHash != ""
			collections = append(collections, c)
		}
	}

	s.JSON(w, http.StatusOK, map[string]any{
		"status":      "ok",
		"collections": collections,
	})
}

// HandleCollectionCreate handles POST /collections
func (s *Server) HandleCollectionCreate(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if sess.AccountID == "" {
		s.Error(w, http.StatusUnauthorized, "not logged in")
		return
	}

	_ = r.ParseForm()
	name := strings.TrimSpace(r.FormValue("name"))
	if name == "" || len(name) > 80 {
		s.Error(w, http.StatusBadRequest, "collection name must be between 1 and 80 characters")
		return
	}

	var pHash string
	if pwd := strings.TrimSpace(r.FormValue("password")); pwd != "" {
		pHash = auth.HashPassword(pwd)
	}

	now := time.Now().Unix()
	colID, err := s.DB.GenerateID(r.Context(), r.FormValue("url_format"))
	if err != nil {
		s.Error(w, http.StatusInternalServerError, "failed to generate collection id")
		return
	}

	_, err = s.DB.Conn().ExecContext(r.Context(),
		"INSERT INTO collections (id, account_id, name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)",
		colID, sess.AccountID, name, pHash, now)
	if err != nil {
		s.Error(w, http.StatusInternalServerError, "failed to create collection")
		return
	}

	// Attach any selected items
	itemIDs := r.Form["item_ids"]
	folderPath := storage.SanitizeFolderPath(r.FormValue("folder_path"))
	for _, itID := range itemIDs {
		itID = strings.TrimSpace(itID)
		if itID != "" {
			_, _ = s.DB.Conn().ExecContext(r.Context(), `
				INSERT INTO collection_items (collection_id, item_id, folder_path, added_at)
				VALUES (?, ?, ?, ?)
				ON CONFLICT(collection_id, item_id) DO UPDATE SET folder_path = excluded.folder_path`,
				colID, itID, folderPath, now)
		}
	}

	baseURL := s.Config.BaseURL
	if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") || r.Header.Get("X-Requested-With") == "XMLHttpRequest" {
		s.JSON(w, http.StatusOK, map[string]any{
			"status":         "ok",
			"collection_id":  colID,
			"collection_url": fmt.Sprintf("%s/c/%s", baseURL, colID),
			"zip_url":        fmt.Sprintf("%s/c/%s/download.zip", baseURL, colID),
		})
		return
	}
	http.Redirect(w, r, fmt.Sprintf("/c/%s", colID), http.StatusFound)
}

// HandleCollectionInitBatch handles POST /collections/init_batch
func (s *Server) HandleCollectionInitBatch(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if sess.AccountID == "" {
		accID, _ := auth.GenerateNumericID(16)
		now := time.Now().Unix()
		_, _ = s.DB.Conn().ExecContext(r.Context(), "INSERT INTO accounts (account_id, created_at) VALUES (?, ?)", accID, now)
		sess.AccountID = accID
		s.SetSession(w, r, sess)
	}

	_ = r.ParseForm()
	name := strings.TrimSpace(r.FormValue("name"))
	customID := strings.TrimSpace(r.FormValue("custom_id"))
	urlFormat := strings.TrimSpace(r.FormValue("url_format"))
	password := strings.TrimSpace(r.FormValue("password"))

	if name == "" {
		var payload struct {
			Name      string `json:"name"`
			CustomID  string `json:"custom_id"`
			URLFormat string `json:"url_format"`
			Password  string `json:"password"`
		}
		if err := s.ReadJSON(r, &payload); err == nil {
			name = strings.TrimSpace(payload.Name)
			customID = strings.TrimSpace(payload.CustomID)
			urlFormat = strings.TrimSpace(payload.URLFormat)
			password = strings.TrimSpace(payload.Password)
		}
	}

	if name == "" {
		name = "new collection"
	}
	if len(name) > 80 {
		name = name[:80]
	}

	var pHash string
	if password != "" {
		pHash = auth.HashPassword(password)
	}

	colID := customID
	var err error
	if colID == "" {
		colID, err = s.DB.GenerateID(r.Context(), urlFormat)
		if err != nil {
			s.Error(w, http.StatusInternalServerError, "failed to generate id")
			return
		}
	} else {
		status := s.DB.CheckIDStatus(r.Context(), colID)
		if status != "available" {
			s.Error(w, http.StatusBadRequest, "custom id not available")
			return
		}
	}

	now := time.Now().Unix()
	_, err = s.DB.Conn().ExecContext(r.Context(),
		"INSERT INTO collections (id, account_id, name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)",
		colID, sess.AccountID, name, pHash, now)
	if err != nil {
		s.Error(w, http.StatusInternalServerError, "failed to create collection")
		return
	}

	baseURL := s.Config.BaseURL
	s.JSON(w, http.StatusOK, map[string]any{
		"id":               colID,
		"name":             name,
		"url":              fmt.Sprintf("%s/c/%s", baseURL, colID),
		"download_zip_url": fmt.Sprintf("%s/c/%s/download.zip", baseURL, colID),
	})
}

// HandleCollectionAuto handles POST /collections/auto
func (s *Server) HandleCollectionAuto(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if sess.AccountID == "" {
		accID, _ := auth.GenerateNumericID(16)
		now := time.Now().Unix()
		_, _ = s.DB.Conn().ExecContext(r.Context(), "INSERT INTO accounts (account_id, created_at) VALUES (?, ?)", accID, now)
		sess.AccountID = accID
		s.SetSession(w, r, sess)
	}

	var payload struct {
		ItemIDs     []string `json:"item_ids"`
		Name        string   `json:"name"`
		Password    string   `json:"password"`
		CustomID    string   `json:"custom_id"`
		URLFormat   string   `json:"url_format"`
		AllowSingle bool     `json:"allow_single"`
	}
	_ = s.ReadJSON(r, &payload)

	minReq := 2
	if payload.AllowSingle {
		minReq = 1
	}
	if len(payload.ItemIDs) < minReq {
		s.JSON(w, http.StatusBadRequest, map[string]any{"error": fmt.Sprintf("At least %d valid upload(s) required.", minReq)})
		return
	}

	name := strings.TrimSpace(payload.Name)
	if name == "" {
		if len(payload.ItemIDs) == 1 {
			name = "1 uploaded file"
		} else {
			name = fmt.Sprintf("%d uploaded files", len(payload.ItemIDs))
		}
	}
	if len(name) > 80 {
		name = name[:80]
	}

	var pHash string
	if payload.Password != "" {
		pHash = auth.HashPassword(payload.Password)
	}

	colID := payload.CustomID
	var err error
	if colID == "" {
		colID, err = s.DB.GenerateID(r.Context(), payload.URLFormat)
		if err != nil {
			s.JSON(w, http.StatusInternalServerError, map[string]any{"error": "failed to generate id"})
			return
		}
	}

	now := time.Now().Unix()
	_, err = s.DB.Conn().ExecContext(r.Context(),
		"INSERT INTO collections (id, account_id, name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)",
		colID, sess.AccountID, name, pHash, now)
	if err != nil {
		s.JSON(w, http.StatusInternalServerError, map[string]any{"error": "failed to create collection"})
		return
	}

	for _, itID := range payload.ItemIDs {
		itID = strings.TrimSpace(itID)
		if itID != "" {
			_, _ = s.DB.Conn().ExecContext(r.Context(), "UPDATE items SET account_id = ? WHERE id = ? AND account_id IS NULL", sess.AccountID, itID)
			_, _ = s.DB.Conn().ExecContext(r.Context(), `
				INSERT INTO collection_items (collection_id, item_id, folder_path, added_at)
				VALUES (?, ?, '', ?)
				ON CONFLICT(collection_id, item_id) DO NOTHING`, colID, itID, now)
		}
	}

	baseURL := s.Config.BaseURL
	s.JSON(w, http.StatusOK, map[string]any{
		"id":               colID,
		"name":             name,
		"url":              fmt.Sprintf("%s/c/%s", baseURL, colID),
		"download_zip_url": fmt.Sprintf("%s/c/%s/download.zip", baseURL, colID),
	})
}

// HandleCollectionRenameJSON handles POST /collections/{id}/name
func (s *Server) HandleCollectionRenameJSON(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if sess.AccountID == "" && !sess.IsAdmin {
		s.JSON(w, http.StatusUnauthorized, map[string]any{"error": "Authentication required."})
		return
	}

	colID := chi.URLParam(r, "id")
	_ = r.ParseForm()
	name := strings.TrimSpace(r.FormValue("name"))
	if name == "" {
		var payload struct {
			Name string `json:"name"`
		}
		if err := s.ReadJSON(r, &payload); err == nil {
			name = strings.TrimSpace(payload.Name)
		}
	}

	if name == "" || len(name) > 80 {
		s.JSON(w, http.StatusBadRequest, map[string]any{"error": "Name must be 1-80 characters."})
		return
	}

	res, err := s.DB.Conn().ExecContext(r.Context(),
		"UPDATE collections SET name = ? WHERE id = ? AND (account_id = ? OR ?)",
		name, colID, sess.AccountID, sess.IsAdmin)
	if err != nil {
		s.JSON(w, http.StatusInternalServerError, map[string]any{"error": "Database error."})
		return
	}
	if aff, _ := res.RowsAffected(); aff == 0 {
		s.JSON(w, http.StatusNotFound, map[string]any{"error": "Collection not found or unauthorized."})
		return
	}

	s.JSON(w, http.StatusOK, map[string]any{"status": "ok", "name": name})
}

// HandleCollectionAddItems handles POST /collections/add-items and POST /collections/{id}/items
func (s *Server) HandleCollectionAddItems(w http.ResponseWriter, r *http.Request) {
	_ = r.ParseForm()

	colID := chi.URLParam(r, "id")
	if colID == "" {
		colID = strings.TrimSpace(r.FormValue("collection_id"))
	}
	if colID == "" {
		http.Redirect(w, r, "/a", http.StatusFound)
		return
	}

	itemIDs := r.Form["item_ids"]
	folderPath := storage.SanitizeFolderPath(r.FormValue("folder_path"))
	now := time.Now().Unix()

	for _, itID := range itemIDs {
		itID = strings.TrimSpace(itID)
		if itID != "" {
			_, _ = s.DB.Conn().ExecContext(r.Context(), `
				INSERT INTO collection_items (collection_id, item_id, folder_path, added_at)
				VALUES (?, ?, ?, ?)
				ON CONFLICT(collection_id, item_id) DO UPDATE SET folder_path = excluded.folder_path`,
				colID, itID, folderPath, now)
		}
	}

	if r.URL.Query().Get("format") == "json" || strings.Contains(r.Header.Get("Accept"), "application/json") || r.Header.Get("X-Requested-With") == "XMLHttpRequest" {
		s.JSON(w, http.StatusOK, map[string]any{"status": "ok", "collection_id": colID})
		return
	}
	http.Redirect(w, r, fmt.Sprintf("/c/%s", colID), http.StatusFound)
}

// HandleCollectionItemsMove handles POST /collections/{id}/items/move
func (s *Server) HandleCollectionItemsMove(w http.ResponseWriter, r *http.Request) {
	colID := chi.URLParam(r, "id")
	_ = r.ParseForm()

	folderPath := storage.SanitizeFolderPath(r.FormValue("folder_path"))
	itemIDs := r.Form["item_ids"]

	for _, itID := range itemIDs {
		itID = strings.TrimSpace(itID)
		if itID != "" {
			_, _ = s.DB.Conn().ExecContext(r.Context(),
				"UPDATE collection_items SET folder_path = ? WHERE collection_id = ? AND item_id = ?",
				folderPath, colID, itID)
		}
	}

	http.Redirect(w, r, fmt.Sprintf("/c/%s", colID), http.StatusFound)
}

// HandleCollectionItemsRemoveSelected handles POST /collections/{id}/items/remove-selected
func (s *Server) HandleCollectionItemsRemoveSelected(w http.ResponseWriter, r *http.Request) {
	colID := chi.URLParam(r, "id")
	_ = r.ParseForm()
	itemIDs := r.Form["item_ids"]

	for _, itID := range itemIDs {
		itID = strings.TrimSpace(itID)
		if itID != "" {
			_, _ = s.DB.Conn().ExecContext(r.Context(),
				"DELETE FROM collection_items WHERE collection_id = ? AND item_id = ?",
				colID, itID)
		}
	}

	http.Redirect(w, r, fmt.Sprintf("/c/%s", colID), http.StatusFound)
}

// HandleCollectionItemRename handles POST /collections/{id}/items/{item_id}/rename
func (s *Server) HandleCollectionItemRename(w http.ResponseWriter, r *http.Request) {
	colID := chi.URLParam(r, "id")
	itemID := chi.URLParam(r, "item_id")
	_ = r.ParseForm()

	newName := storage.SanitizeFilename(r.FormValue("new_name"))
	if newName != "" && itemID != "" {
		item, err := s.DB.GetItem(r.Context(), itemID)
		if err == nil && item != nil && item.Type == "file" {
			oldPath := storage.FilePath(s.UploadDir, item.ID, item.Filename)
			newPath := storage.FilePath(s.UploadDir, item.ID, newName)
			if storage.FileExists(oldPath) {
				_ = storage.MoveFile(oldPath, newPath)
			}
			_, _ = s.DB.Conn().ExecContext(r.Context(), "UPDATE items SET filename = ? WHERE id = ?", newName, itemID)
		}
	}

	http.Redirect(w, r, fmt.Sprintf("/c/%s", colID), http.StatusFound)
}

// HandleCollectionItemRemove handles POST /collections/{id}/items/{item_id}/remove
func (s *Server) HandleCollectionItemRemove(w http.ResponseWriter, r *http.Request) {
	colID := chi.URLParam(r, "id")
	itemID := chi.URLParam(r, "item_id")

	if colID != "" && itemID != "" {
		_, _ = s.DB.Conn().ExecContext(r.Context(),
			"DELETE FROM collection_items WHERE collection_id = ? AND item_id = ?",
			colID, itemID)
	}

	http.Redirect(w, r, fmt.Sprintf("/c/%s", colID), http.StatusFound)
}

// HandleCollectionSettings handles POST /collections/{id}/settings
func (s *Server) HandleCollectionSettings(w http.ResponseWriter, r *http.Request) {
	colID := chi.URLParam(r, "id")
	_ = r.ParseForm()

	name := strings.TrimSpace(r.FormValue("name"))
	if name != "" && len(name) <= 80 {
		_, _ = s.DB.Conn().ExecContext(r.Context(), "UPDATE collections SET name = ? WHERE id = ?", name, colID)
	}

	newPassword := strings.TrimSpace(r.FormValue("password"))
	clearPassword := r.FormValue("clear_password") == "1"
	if clearPassword {
		_, _ = s.DB.Conn().ExecContext(r.Context(), "UPDATE collections SET password_hash = NULL WHERE id = ?", colID)
	} else if newPassword != "" {
		pHash := auth.HashPassword(newPassword)
		_, _ = s.DB.Conn().ExecContext(r.Context(), "UPDATE collections SET password_hash = ? WHERE id = ?", pHash, colID)
	}

	http.Redirect(w, r, fmt.Sprintf("/c/%s", colID), http.StatusFound)
}

// HandleCollectionDelete handles POST /collections/{id}/delete
func (s *Server) HandleCollectionDelete(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if sess.AccountID == "" && !sess.IsAdmin {
		http.Redirect(w, r, "/a", http.StatusFound)
		return
	}

	colID := chi.URLParam(r, "id")
	_ = r.ParseForm()
	deleteContents := r.FormValue("delete_contents") == "1"

	if deleteContents {
		rows, err := s.DB.Conn().QueryContext(r.Context(), `
			SELECT i.id, i.filename, i.type
			FROM items i
			JOIN collection_items ci ON ci.item_id = i.id
			WHERE ci.collection_id = ? AND (i.account_id = ? OR ?)`, colID, sess.AccountID, sess.IsAdmin)
		if err == nil {
			for rows.Next() {
				var itID, fname, itType string
				if rows.Scan(&itID, &fname, &itType) == nil {
					if itType == "file" && fname != "" {
						_ = storage.DeleteFile(storage.FilePath(s.UploadDir, itID, fname))
					}
					_ = s.DB.DeleteItem(r.Context(), itID)
				}
			}
			rows.Close()
		}
	}

	_, _ = s.DB.Conn().ExecContext(r.Context(), "DELETE FROM collection_items WHERE collection_id = ?", colID)
	_, _ = s.DB.Conn().ExecContext(r.Context(), "DELETE FROM collections WHERE id = ? AND (account_id = ? OR ?)", colID, sess.AccountID, sess.IsAdmin)

	http.Redirect(w, r, "/a", http.StatusFound)
}

// HandleCollectionQR handles GET /c/{id}/qr.png
func (s *Server) HandleCollectionQR(w http.ResponseWriter, r *http.Request) {
	colID := chi.URLParam(r, "id")
	targetURL := fmt.Sprintf("%s/c/%s", s.Config.BaseURL, colID)
	pngBytes, err := qrcode.Encode(targetURL, qrcode.Medium, 256)
	if err != nil {
		s.Error(w, http.StatusInternalServerError, "failed to generate qr")
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "public, max-age=86400")
	_, _ = w.Write(pngBytes)
}

// HandleCollectionView handles GET /c/{id}
func (s *Server) HandleCollectionView(w http.ResponseWriter, r *http.Request) {
	colID := chi.URLParam(r, "id")

	var colName, pHash, accID string
	var createdAt int64
	err := s.DB.Conn().QueryRowContext(r.Context(),
		"SELECT name, password_hash, account_id, created_at FROM collections WHERE id = ?", colID).
		Scan(&colName, &pHash, &accID, &createdAt)
	if err != nil {
		s.Error(w, http.StatusNotFound, "collection not found")
		return
	}

	if pHash != "" {
		pwd := r.URL.Query().Get("password")
		if pwd == "" {
			pwd = r.Header.Get("X-Nullhost-Password")
		}
		valid, _ := auth.VerifyPassword(pwd, pHash)
		if !valid {
			s.Error(w, http.StatusUnauthorized, "password required")
			return
		}
	}

	rows, err := s.DB.Conn().QueryContext(r.Context(), `
		SELECT i.id, COALESCE(i.filename, ''), i.type, i.size, COALESCE(ci.folder_path, ''), ci.added_at
		FROM collection_items ci
		JOIN items i ON ci.item_id = i.id
		WHERE ci.collection_id = ?
		ORDER BY ci.folder_path ASC, ci.added_at DESC`, colID)
	if err != nil {
		s.Error(w, http.StatusInternalServerError, "database error")
		return
	}
	defer rows.Close()

	type ItemInCollection struct {
		ID         string `json:"id"`
		Filename   string `json:"filename"`
		Type       string `json:"type"`
		Size       int64  `json:"size"`
		FolderPath string `json:"folder_path"`
		AddedAt    int64  `json:"added_at"`
		RawURL     string `json:"raw_url"`
	}

	var items []ItemInCollection
	baseURL := s.Config.BaseURL

	for rows.Next() {
		var it ItemInCollection
		if err := rows.Scan(&it.ID, &it.Filename, &it.Type, &it.Size, &it.FolderPath, &it.AddedAt); err == nil {
			if it.Filename == "" {
				if it.Type == "paste" {
					it.Filename = "paste.txt"
				} else {
					it.Filename = "file"
				}
			}
			it.RawURL = fmt.Sprintf("%s/raw/%s/%s", baseURL, it.ID, it.Filename)
			items = append(items, it)
		}
	}

	if r.URL.Query().Get("format") == "json" || r.Header.Get("Accept") == "application/json" {
		s.JSON(w, http.StatusOK, map[string]any{
			"status":      "ok",
			"id":          colID,
			"name":        colName,
			"created_at":  createdAt,
			"items_count": len(items),
			"items":       items,
			"zip_url":     fmt.Sprintf("%s/c/%s/download.zip", baseURL, colID),
		})
		return
	}

	s.RenderHTML(w, http.StatusOK, "collection.html", map[string]any{
		"collection": map[string]any{
			"id":           colID,
			"name":         colName,
			"created_at":   createdAt,
			"has_password": pHash != "",
		},
		"items": items,
	})
}
