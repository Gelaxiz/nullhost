package handlers

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
)

// routes mounts all HTTP routes onto s.Router
func (s *Server) routes() {
	r := s.Router

	// Standard middlewares
	r.Use(middleware.RequestID)
	r.Use(middleware.RealIP)
	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)
	r.Use(middleware.GetHead)

	// Static assets serving
	staticDir := "static"
	if _, err := os.Stat(staticDir); os.IsNotExist(err) {
		if _, err := os.Stat(filepath.Join("..", "static")); err == nil {
			staticDir = filepath.Join("..", "static")
		}
	}
	r.Handle("/static/*", http.StripPrefix("/static/", http.FileServer(http.Dir(staticDir))))

	// Web Landing & Static
	r.Get("/", s.HandleIndex)
	r.Get("/lite", s.HandleIndex)
	r.Get("/robots.txt", s.HandleRobots)
	r.Get("/favicon.ico", s.HandleFavicon)
	r.Get("/download-app", s.HandleDownloadApp)
	r.Get("/download-app/{filename}", s.HandleDownloadApp)
	r.Get("/tos", s.HandleTOS)
	r.Get("/privacy", s.HandlePrivacy)
	r.Get("/dmca", s.HandleDMCA)
	r.Get("/about", s.HandleAbout)
	r.Get("/app", s.HandleAppPage)

	// API Endpoints (Nokia J2ME, Symbian & Desktop client compatible)
	r.Get("/api/version", s.HandleAPIVersion)
	r.Get("/api/mobile/version", s.HandleAPIVersion)
	r.Get("/api/check_id", s.HandleCheckID)
	r.Get("/api/check_id/{check_id}", s.HandleCheckID)
	r.Post("/api/mobile/auth", s.HandleMobileAuth)
	r.Get("/api/mobile/items", s.HandleMobileItems)
	r.Post("/api/mobile/upload", s.HandleUpload)
	r.Post("/api/mobile/delete", s.HandleMobileDelete)
	r.Get("/api/client/update", s.HandleClientUpdate)

	// Upload routes
	r.Post("/upload", s.HandleUpload)
	r.Post("/upload_init", s.HandleUploadInit)
	r.Post("/upload_chunk", s.HandleUploadChunk)

	// Retrieval & Delivery routes
	r.Get("/raw/{id}", func(w http.ResponseWriter, req *http.Request) {
		s.HandleRawAndDownload(w, req, false)
	})
	r.Get("/raw/{id}/{filename}", func(w http.ResponseWriter, req *http.Request) {
		s.HandleRawAndDownload(w, req, false)
	})
	r.Get("/download/{id}/{filename}", func(w http.ResponseWriter, req *http.Request) {
		s.HandleRawAndDownload(w, req, true)
	})
	r.Get("/dl/{id}", func(w http.ResponseWriter, req *http.Request) {
		s.HandleRawAndDownload(w, req, true)
	})
	r.Get("/v/{id}", s.HandleOverview)
	r.Get("/v/{id}/{filename}", s.HandleOverview)

	// Collection routes
	r.Get("/collections", s.HandleCollectionsList)
	r.Post("/collections", s.HandleCollectionCreate)
	r.Get("/c/{id}", s.HandleCollectionView)
	r.Get("/c/{id}/download.zip", s.HandleCollectionZip)

	// Account Workspace routes
	r.Get("/a", s.HandleAccountPage)
	r.Post("/a", s.HandleAccountAction)
	r.Get("/account", s.HandleAccountPage)
	r.Post("/account", s.HandleAccountAction)
	r.Post("/account/create", s.HandleAccountCreate)
	r.Post("/account/login", s.HandleAccountLogin)
	r.Post("/account/logout", s.HandleAccountLogout)
	r.Post("/account/nuke", s.HandleAccountNuke)

	// Admin routes
	r.Get("/admin", s.HandleAdminGate)
	r.Post("/admin", s.HandleAdminGate)
	r.Get("/gelaxiz_admin_gate", s.HandleAdminGate)
	r.Post("/gelaxiz_admin_gate", s.HandleAdminGate)
	r.Get("/admin/session_check", s.HandleAdminSessionCheck)
	r.Get("/admin_dashboard", s.HandleAdminDashboard)
	r.Post("/admin/delete", s.HandleAdminDelete)
	r.Post("/admin/takedown", s.HandleAdminTakedown)
	r.Post("/admin/cleanup", s.HandleAdminCleanup)

	// Shortcode direct link redirect (e.g. /{id})
	r.Get("/{id}", func(w http.ResponseWriter, req *http.Request) {
		id := chi.URLParam(req, "id")
		if id == "" {
			s.HandleIndex(w, req)
			return
		}
		http.Redirect(w, req, fmt.Sprintf("/v/%s", id), http.StatusFound)
	})
}

// HandleIndex handles GET /
func (s *Server) HandleIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("format") == "json" || r.Header.Get("Accept") == "application/json" {
		s.JSON(w, http.StatusOK, map[string]any{
			"status":  "ok",
			"service": "nullhost",
			"version": "1.2.0-go",
		})
		return
	}

	sess := s.GetSession(r)
	var collections []map[string]any
	if sess.AccountID != "" {
		rows, err := s.DB.Conn().QueryContext(r.Context(),
			"SELECT id, name FROM collections WHERE account_id = ? ORDER BY created_at DESC", sess.AccountID)
		if err == nil {
			for rows.Next() {
				var id, name string
				if err := rows.Scan(&id, &name); err == nil {
					collections = append(collections, map[string]any{
						"id":   id,
						"name": name,
					})
				}
			}
			rows.Close()
		}
	}

	s.RenderHTML(w, http.StatusOK, "index.html", map[string]any{
		"logged_in":   sess.AccountID != "",
		"collections": collections,
	})
}

// HandleAccountPage handles GET /a and GET /account (The user workspace)
func (s *Server) HandleAccountPage(w http.ResponseWriter, r *http.Request) {
	sess := s.GetSession(r)
	if sess.AccountID == "" {
		s.RenderHTML(w, http.StatusOK, "account_login.html", map[string]any{})
		return
	}

	ctx := r.Context()
	accID := sess.AccountID

	// Fetch items
	rows, err := s.DB.Conn().QueryContext(ctx, `
		SELECT id, type, COALESCE(filename, ''), size, created_at, expires_at, views, COALESCE(password_hash, ''), is_public
		FROM items
		WHERE account_id = ?
		ORDER BY created_at DESC`, accID)

	var items []map[string]any
	if err == nil {
		for rows.Next() {
			var id, itType, fname, pHash string
			var size, createdAt, expiresAt, views int64
			var isPublic int
			if err := rows.Scan(&id, &itType, &fname, &size, &createdAt, &expiresAt, &views, &pHash, &isPublic); err == nil {
				if fname == "" {
					if itType == "paste" {
						fname = "paste.txt"
					} else {
						fname = "file"
					}
				}
				displayType := "file"
				if itType == "paste" {
					displayType = "paste"
				} else if strings.HasSuffix(strings.ToLower(fname), ".jpg") || strings.HasSuffix(strings.ToLower(fname), ".png") || strings.HasSuffix(strings.ToLower(fname), ".webp") {
					displayType = "image"
				}

				items = append(items, map[string]any{
					"id":           id,
					"type":         itType,
					"filename":     fname,
					"label":        fname,
					"size":         size,
					"created_at":   createdAt,
					"expires_at":   expiresAt,
					"views":        views,
					"is_public":    isPublic,
					"has_password": pHash != "",
					"display_type": displayType,
				})
			}
		}
		rows.Close()
	}

	// Fetch collections
	colRows, err := s.DB.Conn().QueryContext(ctx, `
		SELECT id, name, created_at, COALESCE(password_hash, '')
		FROM collections
		WHERE account_id = ?
		ORDER BY created_at DESC`, accID)

	var collections []map[string]any
	if err == nil {
		for colRows.Next() {
			var id, name, pHash string
			var createdAt int64
			if err := colRows.Scan(&id, &name, &createdAt, &pHash); err == nil {
				collections = append(collections, map[string]any{
					"id":           id,
					"name":         name,
					"created_at":   createdAt,
					"has_password": pHash != "",
				})
			}
		}
		colRows.Close()
	}

	s.RenderHTML(w, http.StatusOK, "account.html", map[string]any{
		"account_id":  accID,
		"items":       items,
		"collections": collections,
		"logged_in":   true,
	})
}

// HandleAccountAction handles POST /a and POST /account
func (s *Server) HandleAccountAction(w http.ResponseWriter, r *http.Request) {
	_ = r.ParseForm()
	action := r.FormValue("action")
	if action == "logout" {
		s.ClearSession(w)
		http.Redirect(w, r, "/a", http.StatusFound)
		return
	}

	accID := strings.TrimSpace(r.FormValue("account_id"))
	if accID != "" {
		var exists int
		_ = s.DB.Conn().QueryRowContext(r.Context(), "SELECT COUNT(*) FROM accounts WHERE account_id = ?", accID).Scan(&exists)
		if exists > 0 {
			sess := s.GetSession(r)
			sess.AccountID = accID
			s.SetSession(w, r, sess)
			http.Redirect(w, r, "/a", http.StatusFound)
			return
		}
	}

	http.Redirect(w, r, "/a", http.StatusFound)
}

// HandleRobots handles /robots.txt
func (s *Server) HandleRobots(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	fmt.Fprintln(w, "User-agent: *\nDisallow: /raw/\nDisallow: /download/\nDisallow: /admin\nDisallow: /v/\nDisallow: /c/")
}

// HandleFavicon handles /favicon.ico
func (s *Server) HandleFavicon(w http.ResponseWriter, r *http.Request) {
	iconPath := "static/icon-192.png"
	if _, err := os.Stat(iconPath); os.IsNotExist(err) {
		iconPath = "../static/icon-192.png"
	}
	http.ServeFile(w, r, iconPath)
}

// HandleDownloadApp handles downloads for client apps
func (s *Server) HandleDownloadApp(w http.ResponseWriter, r *http.Request) {
	filename := chi.URLParam(r, "filename")
	filenameLower := strings.ToLower(filename)

	var target string
	switch {
	case strings.Contains(filenameLower, "j2me") || strings.HasSuffix(filenameLower, ".jar"):
		target = "https://github.com/Gelaxiz/nullhost/releases/latest/download/nullhost.jar"
	case strings.Contains(filenameLower, "jad") || strings.HasSuffix(filenameLower, ".jad"):
		target = "https://github.com/Gelaxiz/nullhost/releases/latest/download/nullhost.jad"
	case strings.Contains(filenameLower, "windows") || strings.HasSuffix(filenameLower, ".exe"):
		target = "https://github.com/Gelaxiz/nullhost/releases/latest/download/nullhost-setup.exe"
	default:
		target = "https://github.com/Gelaxiz/nullhost/releases/latest"
	}

	http.Redirect(w, r, target, http.StatusFound)
}

func (s *Server) HandleTOS(w http.ResponseWriter, r *http.Request) {
	s.RenderHTML(w, http.StatusOK, "tos.html", nil)
}

func (s *Server) HandlePrivacy(w http.ResponseWriter, r *http.Request) {
	s.RenderHTML(w, http.StatusOK, "privacy.html", nil)
}

func (s *Server) HandleDMCA(w http.ResponseWriter, r *http.Request) {
	s.RenderHTML(w, http.StatusOK, "dmca.html", nil)
}

func (s *Server) HandleAbout(w http.ResponseWriter, r *http.Request) {
	s.RenderHTML(w, http.StatusOK, "about.html", nil)
}

func (s *Server) HandleAppPage(w http.ResponseWriter, r *http.Request) {
	s.RenderHTML(w, http.StatusOK, "app.html", nil)
}
