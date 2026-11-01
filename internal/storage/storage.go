package storage

import (
	"archive/zip"
	"fmt"
	"image"
	"image/jpeg"
	"image/png"
	"io"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"

	_ "golang.org/x/image/webp"
)

var (
	unsafeCharsRegex     = regexp.MustCompile(`[^a-zA-Z0-9._-]+`)
	multiDotRegex        = regexp.MustCompile(`\.+`)
	multiUnderscoreRegex = regexp.MustCompile(`_+`)
)

type CollectionZipEntry struct {
	ItemID     string
	Filename   string
	FolderPath string
	Type       string // "file" or "paste"
	Content    string // For paste items
}

// SanitizeFilename ensures filenames are clean, safe, and free from directory traversal
func SanitizeFilename(name string) string {
	cleaned := strings.TrimSpace(name)
	cleaned = filepath.Base(cleaned)
	if cleaned == "" || cleaned == "." || cleaned == ".." {
		return "file"
	}

	cleaned = strings.ReplaceAll(cleaned, " ", "_")
	cleaned = unsafeCharsRegex.ReplaceAllString(cleaned, "_")
	cleaned = multiUnderscoreRegex.ReplaceAllString(cleaned, "_")
	cleaned = multiDotRegex.ReplaceAllString(cleaned, ".")

	ext := filepath.Ext(cleaned)
	stem := strings.TrimSuffix(cleaned, ext)
	stem = strings.Trim(stem, "._-")
	ext = strings.Trim(ext, "_-")
	if ext == "." {
		ext = ""
	}

	if stem == "" {
		if ext == "" {
			return "file"
		}
		stem = "file"
	}

	cleaned = stem + ext
	if cleaned == "" {
		return "file"
	}

	if len(cleaned) > 200 {
		maxStemLen := 200 - len(ext)
		if maxStemLen > 0 && len(stem) > maxStemLen {
			stem = stem[:maxStemLen]
		}
		cleaned = stem + ext
	}

	return cleaned
}

// SanitizeFolderPath cleans and limits collection directory hierarchies
func SanitizeFolderPath(rawPath string) string {
	parts := strings.Split(strings.ReplaceAll(rawPath, "\\", "/"), "/")
	cleanedParts := make([]string, 0, len(parts))

	for _, part := range parts {
		cleaned := SanitizeFilename(strings.TrimSpace(part))
		if cleaned != "" && cleaned != "file" && cleaned != "." && cleaned != ".." {
			cleanedParts = append(cleanedParts, cleaned)
		}
	}

	if len(cleanedParts) > 8 {
		cleanedParts = cleanedParts[:8]
	}

	res := strings.Join(cleanedParts, "/")
	if len(res) > 240 {
		res = res[:240]
	}
	return res
}

// FilePath returns the absolute on-disk path for an uploaded item
func FilePath(uploadDir, itemID, filename string) string {
	safeFilename := SanitizeFilename(filename)
	return filepath.Join(uploadDir, itemID+"_"+safeFilename)
}

// SaveFile writes an incoming stream directly to the target destination
func SaveFile(destPath string, r io.Reader) (int64, error) {
	if err := os.MkdirAll(filepath.Dir(destPath), 0755); err != nil {
		return 0, fmt.Errorf("failed to create upload directory: %w", err)
	}

	out, err := os.Create(destPath)
	if err != nil {
		return 0, fmt.Errorf("failed to create destination file: %w", err)
	}
	defer out.Close()

	n, err := io.Copy(out, r)
	if err != nil {
		return 0, fmt.Errorf("failed to write file content: %w", err)
	}

	return n, nil
}

// SaveChunk appends or creates a chunk file directly at the target destination
func SaveChunk(destPath string, chunkIndex int, r io.Reader) (int64, error) {
	if err := os.MkdirAll(filepath.Dir(destPath), 0755); err != nil {
		return 0, fmt.Errorf("failed to create upload directory: %w", err)
	}

	var flags int
	if chunkIndex == 0 {
		flags = os.O_CREATE | os.O_WRONLY | os.O_TRUNC
	} else {
		flags = os.O_WRONLY | os.O_APPEND
	}

	f, err := os.OpenFile(destPath, flags, 0644)
	if err != nil {
		return 0, fmt.Errorf("failed to open chunk target file: %w", err)
	}
	defer f.Close()

	if _, err := io.Copy(f, r); err != nil {
		return 0, fmt.Errorf("failed to write chunk content: %w", err)
	}

	fi, err := f.Stat()
	if err != nil {
		return 0, fmt.Errorf("failed to stat file after chunk write: %w", err)
	}

	return fi.Size(), nil
}

// StripEXIF decodes an image and re-encodes it cleanly without any EXIF or camera metadata
func StripEXIF(filePath string) error {
	f, err := os.Open(filePath)
	if err != nil {
		return err
	}
	defer f.Close()

	img, format, err := image.Decode(f)
	if err != nil {
		// Not a supported image format or corrupted; leave original untouched
		return nil
	}

	bounds := img.Bounds()
	if bounds.Dx()*bounds.Dy() > 25000000 {
		// Too large to re-encode in memory (> 25 megapixels)
		return nil
	}

	tmpFile := filePath + ".exif_tmp"
	out, err := os.Create(tmpFile)
	if err != nil {
		return err
	}

	var encodeErr error
	switch strings.ToLower(format) {
	case "jpeg", "jpg":
		encodeErr = jpeg.Encode(out, img, &jpeg.Options{Quality: 95})
	case "png":
		encodeErr = png.Encode(out, img)
	default:
		out.Close()
		os.Remove(tmpFile)
		return nil
	}

	closeErr := out.Close()
	if encodeErr != nil {
		os.Remove(tmpFile)
		return encodeErr
	}
	if closeErr != nil {
		os.Remove(tmpFile)
		return closeErr
	}

	return os.Rename(tmpFile, filePath)
}

// StreamZipCollection streams a collection archive directly as a ZIP stream
func StreamZipCollection(w io.Writer, uploadDir string, items []CollectionZipEntry) error {
	zw := zip.NewWriter(w)
	defer zw.Close()

	usedPaths := make(map[string]bool)

	for _, item := range items {
		folder := SanitizeFolderPath(item.FolderPath)
		filename := SanitizeFilename(item.Filename)
		if filename == "" {
			filename = item.ItemID
		}

		archiveName := filename
		if folder != "" {
			archiveName = path.Join(folder, filename)
		}

		if usedPaths[archiveName] {
			ext := path.Ext(filename)
			stem := strings.TrimSuffix(filename, ext)
			archiveName = stem + "-" + item.ItemID + ext
			if folder != "" {
				archiveName = path.Join(folder, archiveName)
			}
		}
		usedPaths[archiveName] = true

		header := &zip.FileHeader{
			Name:   archiveName,
			Method: zip.Deflate,
		}

		writer, err := zw.CreateHeader(header)
		if err != nil {
			return fmt.Errorf("failed to create zip header for %s: %w", archiveName, err)
		}

		if item.Type == "file" {
			srcPath := FilePath(uploadDir, item.ItemID, item.Filename)
			if srcFile, err := os.Open(srcPath); err == nil {
				_, _ = io.Copy(writer, srcFile)
				srcFile.Close()
			}
		} else {
			// Paste item
			_, _ = io.WriteString(writer, item.Content)
		}
	}

	return nil
}

// DeleteFile removes an uploaded file from disk if it exists
func DeleteFile(destPath string) error {
	if _, err := os.Stat(destPath); os.IsNotExist(err) {
		return nil
	}
	return os.Remove(destPath)
}
