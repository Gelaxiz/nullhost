package handlers

import (
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
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
	s.JSON(w, http.StatusOK, map[string]any{
		"status":         "ok",
		"collection_id":  colID,
		"collection_url": fmt.Sprintf("%s/c/%s", baseURL, colID),
		"zip_url":        fmt.Sprintf("%s/c/%s/download.zip", baseURL, colID),
	})
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
