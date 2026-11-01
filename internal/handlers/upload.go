package handlers

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"nullhost/internal/auth"
	"nullhost/internal/models"
	"nullhost/internal/storage"
)

// GenerateDeleteToken creates a cryptographically secure 24-byte hex token and its SHA256 hash
func GenerateDeleteToken() (string, string) {
	b := make([]byte, 18)
	_, _ = rand.Read(b)
	token := hex.EncodeToString(b)
	h := sha256.Sum256([]byte(token))
	return token, fmt.Sprintf("%x", h)
}

// GenerateUploadToken produces an HMAC signed token for chunk assembly
func (s *Server) GenerateUploadToken(itemID, uploadID string, totalChunks int, timestamp int64) string {
	payload := fmt.Sprintf("%s:%s:%d:%d", itemID, uploadID, totalChunks, timestamp)
	h := hmac.New(sha256.New, []byte(s.Config.SecretKey))
	h.Write([]byte(payload))
	sig := hex.EncodeToString(h.Sum(nil))
	return fmt.Sprintf("%s:%s", payload, sig)
}

// VerifyUploadToken verifies the HMAC signature and expiration (4 hours)
func (s *Server) VerifyUploadToken(token string) (itemID, uploadID string, totalChunks int, ok bool) {
	parts := strings.Split(token, ":")
	if len(parts) != 5 {
		return "", "", 0, false
	}
	itemID, uploadID, totalStr, tsStr, sig := parts[0], parts[1], parts[2], parts[3], parts[4]

	total, err := strconv.Atoi(totalStr)
	if err != nil || total < 1 {
		return "", "", 0, false
	}
	ts, err := strconv.ParseInt(tsStr, 10, 64)
	if err != nil || time.Now().Unix()-ts > 14400 {
		return "", "", 0, false
	}

	payload := fmt.Sprintf("%s:%s:%d:%d", itemID, uploadID, total, ts)
	h := hmac.New(sha256.New, []byte(s.Config.SecretKey))
	h.Write([]byte(payload))
	expectedSig := hex.EncodeToString(h.Sum(nil))

	if !hmac.Equal([]byte(sig), []byte(expectedSig)) {
		return "", "", 0, false
	}

	return itemID, uploadID, total, true
}

// HandleUpload handles single file or paste uploads via /upload
func (s *Server) HandleUpload(w http.ResponseWriter, r *http.Request) {
	// Parse multipart with 64MB memory limit (streams rest to disk)
	_ = r.ParseMultipartForm(64 << 20)

	sess := s.GetSession(r)
	accountID := sess.AccountID
	if accountID == "" {
		accountID = strings.TrimSpace(r.Header.Get("X-Nullhost-Account"))
	}
	if accountID == "" {
		accountID = strings.TrimSpace(r.FormValue("account_id"))
	}

	now := time.Now().Unix()
	customID := strings.TrimSpace(r.FormValue("custom_id"))
	urlFormat := strings.TrimSpace(r.FormValue("url_format"))

	var itemID string
	var err error

	if customID != "" {
		status := s.DB.CheckIDStatus(r.Context(), customID)
		if status != "available" {
			s.Error(w, http.StatusBadRequest, fmt.Sprintf("custom id error: %s", status))
			return
		}
		itemID = customID
	} else {
		itemID, err = s.DB.GenerateID(r.Context(), urlFormat)
		if err != nil {
			s.Error(w, http.StatusInternalServerError, "failed to generate unique id")
			return
		}
	}

	expiryStr := r.FormValue("expiry")
	if expiryStr == "" {
		expiryStr = "86400"
	}
	expiresAt := s.CalculateExpiry(expiryStr, r.FormValue("custom_expiry_days"), accountID, now)

	burnLimit, _ := strconv.ParseInt(r.FormValue("burn_limit"), 10, 64)
	dynamicExpiry := 0
	if r.FormValue("dynamic_expiry") == "on" || r.FormValue("dynamic_expiry") == "1" {
		dynamicExpiry = 1
	}

	isPublic := 0
	if accountID != "" && (r.FormValue("is_public") == "on" || r.FormValue("is_public") == "1") {
		isPublic = 1
	}

	var passwordHash string
	if pwd := strings.TrimSpace(r.FormValue("password")); pwd != "" {
		passwordHash = auth.HashPassword(pwd)
	}

	deleteToken, deleteTokenHash := GenerateDeleteToken()

	// Check if this is a file or paste
	file, fileHeader, fileErr := r.FormFile("file")
	isPaste := fileErr != nil || fileHeader == nil || fileHeader.Filename == ""
	pasteContent := r.FormValue("content")

	var filename string
	var mimeType string
	var fileSize int64
	itemType := "file"

	if isPaste {
		itemType = "paste"
		filename = "paste.txt"
		mimeType = "text/plain; charset=utf-8"
		fileSize = int64(len(pasteContent))
	} else {
		defer file.Close()
		customFilename := strings.TrimSpace(r.FormValue("custom_filename"))
		if customFilename != "" {
			filename = storage.SanitizeFilename(customFilename)
		} else {
			filename = storage.SanitizeFilename(fileHeader.Filename)
		}
		mimeType = fileHeader.Header.Get("Content-Type")
		if mimeType == "" {
			mimeType = "application/octet-stream"
		}

		destPath := storage.FilePath(s.UploadDir, itemID, filename)
		n, err := storage.SaveFile(destPath, file)
		if err != nil {
			s.Error(w, http.StatusInternalServerError, "failed to save file")
			return
		}
		fileSize = n

		if r.FormValue("scrub_exif") == "on" || r.FormValue("scrub_exif") == "1" {
			_ = storage.StripEXIF(destPath)
			if fi, err := os.Stat(destPath); err == nil {
				fileSize = fi.Size()
			}
		}
	}

	item := &models.Item{
		ID:              itemID,
		Type:            itemType,
		AccountID:       accountID,
		Filename:        filename,
		Content:         pasteContent,
		PasswordHash:    passwordHash,
		BurnAfterRead:   burnLimit,
		ExpiresAt:       expiresAt,
		Views:           0,
		Size:            fileSize,
		MimeType:        mimeType,
		CreatedAt:       now,
		IsPublic:        isPublic,
		DynamicExpiry:   dynamicExpiry,
		IsPartial:       0,
		DeleteTokenHash: deleteTokenHash,
	}

	if err := s.DB.InsertItem(r.Context(), item); err != nil {
		s.Error(w, http.StatusInternalServerError, "failed to record upload in database")
		return
	}

	baseURL := s.Config.BaseURL
	viewURL := fmt.Sprintf("%s/v/%s", baseURL, itemID)
	rawURL := fmt.Sprintf("%s/raw/%s/%s", baseURL, itemID, filename)
	downloadURL := fmt.Sprintf("%s/download/%s/%s", baseURL, itemID, filename)

	s.JSON(w, http.StatusOK, map[string]any{
		"success":      true,
		"id":           itemID,
		"url":          viewURL,
		"raw_url":      rawURL,
		"download_url": downloadURL,
		"delete_token": deleteToken,
		"filename":     filename,
		"size":         fileSize,
		"expires_at":   expiresAt,
	})
}

// HandleUploadInit pre-allocates an item for chunked upload
func (s *Server) HandleUploadInit(w http.ResponseWriter, r *http.Request) {
	_ = r.ParseForm()
	uploadID := strings.TrimSpace(r.FormValue("upload_id"))
	if uploadID == "" {
		s.Error(w, http.StatusBadRequest, "invalid upload_id")
		return
	}

	totalChunks, err := strconv.Atoi(r.FormValue("total_chunks"))
	if err != nil || totalChunks < 1 || totalChunks > 10000 {
		s.Error(w, http.StatusBadRequest, "invalid chunk range")
		return
	}

	sess := s.GetSession(r)
	accountID := sess.AccountID
	now := time.Now().Unix()

	itemID, err := s.DB.GenerateID(r.Context(), r.FormValue("url_format"))
	if err != nil {
		s.Error(w, http.StatusInternalServerError, "failed to generate id")
		return
	}

	filename := storage.SanitizeFilename(r.FormValue("filename"))
	_, deleteTokenHash := GenerateDeleteToken()
	expiresAt := s.CalculateExpiry(r.FormValue("expiry"), "", accountID, now)

	item := &models.Item{
		ID:              itemID,
		Type:            "file",
		AccountID:       accountID,
		Filename:        filename,
		ExpiresAt:       expiresAt,
		CreatedAt:       now,
		IsPartial:       1,
		DeleteTokenHash: deleteTokenHash,
	}

	if err := s.DB.InsertItem(r.Context(), item); err != nil {
		s.Error(w, http.StatusInternalServerError, "failed to initialize upload")
		return
	}

	uploadToken := s.GenerateUploadToken(itemID, uploadID, totalChunks, now)
	baseURL := s.Config.BaseURL

	s.JSON(w, http.StatusOK, map[string]any{
		"status":       "ready",
		"id":           itemID,
		"upload_token": uploadToken,
		"url":          fmt.Sprintf("%s/v/%s", baseURL, itemID),
		"raw_url":      fmt.Sprintf("%s/raw/%s/%s", baseURL, itemID, filename),
		"download_url": fmt.Sprintf("%s/download/%s/%s", baseURL, itemID, filename),
	})
}

// HandleUploadChunk handles sequential chunk uploads
func (s *Server) HandleUploadChunk(w http.ResponseWriter, r *http.Request) {
	_ = r.ParseMultipartForm(32 << 20)

	uploadToken := strings.TrimSpace(r.FormValue("upload_token"))
	itemID, uploadID, totalChunks, ok := s.VerifyUploadToken(uploadToken)
	if !ok {
		s.Error(w, http.StatusForbidden, "invalid or expired upload token")
		return
	}

	chunkIndex, err := strconv.Atoi(r.FormValue("chunk_index"))
	if err != nil || chunkIndex < 0 || chunkIndex >= totalChunks {
		s.Error(w, http.StatusBadRequest, "invalid chunk index")
		return
	}

	file, _, err := r.FormFile("file")
	if err != nil {
		s.Error(w, http.StatusBadRequest, "no file chunk provided")
		return
	}
	defer file.Close()

	item, err := s.DB.GetItem(r.Context(), itemID)
	if err != nil || item == nil {
		s.Error(w, http.StatusNotFound, "upload item not found")
		return
	}

	destPath := storage.FilePath(s.UploadDir, item.ID, item.Filename)
	newSize, err := storage.SaveChunk(destPath, chunkIndex, file)
	if err != nil {
		s.Error(w, http.StatusInternalServerError, "failed to append chunk")
		return
	}

	// Update size
	_, _ = s.DB.Conn().ExecContext(r.Context(), "UPDATE items SET size = ? WHERE id = ?", newSize, itemID)

	baseURL := s.Config.BaseURL
	viewURL := fmt.Sprintf("%s/v/%s", baseURL, itemID)
	rawURL := fmt.Sprintf("%s/raw/%s/%s", baseURL, itemID, item.Filename)
	downloadURL := fmt.Sprintf("%s/download/%s/%s", baseURL, itemID, item.Filename)

	if chunkIndex < totalChunks-1 {
		s.JSON(w, http.StatusOK, map[string]any{
			"status":       "chunk_received",
			"index":        chunkIndex,
			"id":           itemID,
			"url":          viewURL,
			"raw_url":      rawURL,
			"download_url": downloadURL,
		})
		return
	}

	// Final chunk received
	if r.FormValue("scrub_exif") == "on" || r.FormValue("scrub_exif") == "1" {
		_ = storage.StripEXIF(destPath)
		if fi, err := os.Stat(destPath); err == nil {
			newSize = fi.Size()
		}
	}

	_, _ = s.DB.Conn().ExecContext(r.Context(), "UPDATE items SET is_partial = 0, size = ? WHERE id = ?", newSize, itemID)

	s.JSON(w, http.StatusOK, map[string]any{
		"status":       "complete",
		"id":           itemID,
		"url":          viewURL,
		"raw_url":      rawURL,
		"download_url": downloadURL,
		"size":         newSize,
		"upload_id":    uploadID,
	})
}
