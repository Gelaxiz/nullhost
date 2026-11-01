package handlers

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"nullhost/internal/auth"
	"nullhost/internal/config"
	"nullhost/internal/database"
)

func setupTestServer(t *testing.T) (*Server, func()) {
	t.Helper()
	tmpDir, err := os.MkdirTemp("", "nullhost-handler-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}

	dbPath := filepath.Join(tmpDir, "test.db")
	db, err := database.OpenDB(dbPath)
	if err != nil {
		os.RemoveAll(tmpDir)
		t.Fatalf("failed to open test db: %v", err)
	}

	uploadDir := filepath.Join(tmpDir, "uploads")
	os.MkdirAll(uploadDir, 0755)

	cfg := &config.Config{
		Host:            "127.0.0.1",
		Port:            "5005",
		SiteDomain:      "127.0.0.1:5005",
		BaseURL:         "http://127.0.0.1:5005",
		SecretKey:       "test-secret-key-1234567890",
		AdminPassword:   "Syyskuu_05",
		AdminTOTPSecret: "VTAE42SXAHBHR3EZ",
		UploadFolder:    uploadDir,
		DBPath:          dbPath,
	}

	srv := NewServer(cfg, db)

	cleanup := func() {
		db.Close()
		os.RemoveAll(tmpDir)
	}

	return srv, cleanup
}

func TestHandlers_APIVersion(t *testing.T) {
	srv, cleanup := setupTestServer(t)
	defer cleanup()

	req := httptest.NewRequest(http.MethodGet, "/api/version", nil)
	rec := httptest.NewRecorder()
	srv.Router.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}

	var res map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &res); err != nil {
		t.Fatalf("failed to parse JSON: %v", err)
	}

	if res["version"] != "1.2.0" {
		t.Errorf("expected version 1.2.0, got %v", res["version"])
	}
}

func TestHandlers_CheckID(t *testing.T) {
	srv, cleanup := setupTestServer(t)
	defer cleanup()

	// 1. Available custom ID
	req := httptest.NewRequest(http.MethodGet, "/api/check_id/my-test-id", nil)
	rec := httptest.NewRecorder()
	srv.Router.ServeHTTP(rec, req)

	var res map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &res)
	if res["status"] != "available" || res["available"] != true {
		t.Errorf("expected available, got %v", res)
	}

	// 2. Reserved ID
	req2 := httptest.NewRequest(http.MethodGet, "/api/check_id/admin", nil)
	rec2 := httptest.NewRecorder()
	srv.Router.ServeHTTP(rec2, req2)
	_ = json.Unmarshal(rec2.Body.Bytes(), &res)
	if res["status"] != "reserved" || res["available"] != false {
		t.Errorf("expected reserved, got %v", res)
	}
}

func TestHandlers_UploadAndDownload(t *testing.T) {
	srv, cleanup := setupTestServer(t)
	defer cleanup()

	// Multipart upload
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)

	part, err := writer.CreateFormFile("file", "hello.txt")
	if err != nil {
		t.Fatalf("failed to create form file: %v", err)
	}
	_, _ = io.WriteString(part, "Hello Nullhost Go!")
	_ = writer.WriteField("custom_id", "myhello")
	_ = writer.Close()

	uploadReq := httptest.NewRequest(http.MethodPost, "/upload", &body)
	uploadReq.Header.Set("Content-Type", writer.FormDataContentType())
	uploadRec := httptest.NewRecorder()
	srv.Router.ServeHTTP(uploadRec, uploadReq)

	if uploadRec.Code != http.StatusOK {
		t.Fatalf("upload failed with %d: %s", uploadRec.Code, uploadRec.Body.String())
	}

	var upRes map[string]any
	if err := json.Unmarshal(uploadRec.Body.Bytes(), &upRes); err != nil {
		t.Fatalf("failed to parse upload response: %v", err)
	}
	if upRes["id"] != "myhello" {
		t.Errorf("expected id myhello, got %v", upRes["id"])
	}

	// Raw download
	dlReq := httptest.NewRequest(http.MethodGet, "/raw/myhello/hello.txt", nil)
	dlRec := httptest.NewRecorder()
	srv.Router.ServeHTTP(dlRec, dlReq)

	if dlRec.Code != http.StatusOK {
		t.Fatalf("download failed with %d", dlRec.Code)
	}
	if dlRec.Body.String() != "Hello Nullhost Go!" {
		t.Errorf("expected 'Hello Nullhost Go!', got %q", dlRec.Body.String())
	}
}

func TestHandlers_AdminAuthAndDashboard(t *testing.T) {
	srv, cleanup := setupTestServer(t)
	defer cleanup()

	// Compute TOTP code
	totpCode, err := auth.GenerateTOTP(srv.Config.AdminTOTPSecret, time.Now().Unix(), 6)
	if err != nil {
		t.Fatalf("failed to generate totp: %v", err)
	}

	form := fmt.Sprintf("password=Syyskuu_05&totp=%s", totpCode)
	loginReq := httptest.NewRequest(http.MethodPost, "/admin", strings.NewReader(form))
	loginReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	loginRec := httptest.NewRecorder()
	srv.Router.ServeHTTP(loginRec, loginReq)

	if loginRec.Code != http.StatusOK {
		t.Fatalf("admin login failed with %d: %s", loginRec.Code, loginRec.Body.String())
	}

	// Capture session cookie
	cookies := loginRec.Result().Cookies()
	var sessCookie *http.Cookie
	for _, c := range cookies {
		if c.Name == "nullhost_session" {
			sessCookie = c
			break
		}
	}
	if sessCookie == nil {
		t.Fatalf("missing nullhost_session cookie after admin login")
	}

	// Query dashboard
	dashReq := httptest.NewRequest(http.MethodGet, "/admin_dashboard?format=json", nil)
	dashReq.AddCookie(sessCookie)
	dashRec := httptest.NewRecorder()
	srv.Router.ServeHTTP(dashRec, dashReq)

	if dashRec.Code != http.StatusOK {
		t.Fatalf("dashboard failed with %d: %s", dashRec.Code, dashRec.Body.String())
	}

	var dashRes map[string]any
	if err := json.Unmarshal(dashRec.Body.Bytes(), &dashRes); err != nil {
		t.Fatalf("failed to parse dashboard json: %v", err)
	}
	if dashRes["status"] != "ok" {
		t.Errorf("expected status ok, got %v", dashRes["status"])
	}
}

func TestHandlers_AccountLifecycleAndNuke(t *testing.T) {
	srv, cleanup := setupTestServer(t)
	defer cleanup()

	// 1. Create 16-digit account
	createReq := httptest.NewRequest(http.MethodPost, "/account/create", strings.NewReader("digits=16"))
	createReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	createRec := httptest.NewRecorder()
	srv.Router.ServeHTTP(createRec, createReq)

	if createRec.Code != http.StatusOK {
		t.Fatalf("account create failed with %d", createRec.Code)
	}

	var accRes map[string]any
	_ = json.Unmarshal(createRec.Body.Bytes(), &accRes)
	accID := accRes["account_id"].(string)
	if len(accID) != 16 {
		t.Errorf("expected 16-digit account id, got %q", accID)
	}

	// Capture session cookie
	var sessCookie *http.Cookie
	for _, c := range createRec.Result().Cookies() {
		if c.Name == "nullhost_session" {
			sessCookie = c
			break
		}
	}

	// 2. Upload an item under this account
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, _ := writer.CreateFormFile("file", "doc.txt")
	_, _ = io.WriteString(part, "Important user document")
	_ = writer.WriteField("custom_id", "my-account-doc")
	_ = writer.Close()

	upReq := httptest.NewRequest(http.MethodPost, "/upload", &body)
	upReq.Header.Set("Content-Type", writer.FormDataContentType())
	upReq.AddCookie(sessCookie)
	upRec := httptest.NewRecorder()
	srv.Router.ServeHTTP(upRec, upReq)

	if upRec.Code != http.StatusOK {
		t.Fatalf("upload failed: %s", upRec.Body.String())
	}

	// 3. Nuke account
	nukeReq := httptest.NewRequest(http.MethodPost, "/account/nuke", strings.NewReader("confirmation=NUKE"))
	nukeReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	nukeReq.AddCookie(sessCookie)
	nukeRec := httptest.NewRecorder()
	srv.Router.ServeHTTP(nukeRec, nukeReq)

	if nukeRec.Code != http.StatusOK {
		t.Fatalf("nuke failed with %d: %s", nukeRec.Code, nukeRec.Body.String())
	}

	// 4. Verify item is gone and permanently retired
	isRetired, err := srv.DB.IsRetired(nukeReq.Context(), "my-account-doc")
	if err != nil || !isRetired {
		t.Errorf("expected nuked item ID to be permanently retired")
	}

	// 5. Verify downloading returns 404 retired
	dlReq := httptest.NewRequest(http.MethodGet, "/raw/my-account-doc/doc.txt", nil)
	dlRec := httptest.NewRecorder()
	srv.Router.ServeHTTP(dlRec, dlReq)
	if dlRec.Code != http.StatusNotFound {
		t.Errorf("expected 404 on nuked item, got %d", dlRec.Code)
	}
}
