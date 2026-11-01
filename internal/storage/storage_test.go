package storage

import (
	"archive/zip"
	"bytes"
	"image"
	"image/color"
	"image/png"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestStorage_SanitizeFilename(t *testing.T) {
	cases := []struct {
		input    string
		expected string
	}{
		{"../../etc/passwd", "passwd"},
		{"test file (1).jpg", "test_file_1.jpg"},
		{"..", "file"},
		{"", "file"},
		{"valid_name-123.png", "valid_name-123.png"},
		{"hello...world.txt", "hello.world.txt"},
	}

	for _, c := range cases {
		out := SanitizeFilename(c.input)
		if out != c.expected {
			t.Errorf("SanitizeFilename(%q): expected %q, got %q", c.input, c.expected, out)
		}
	}
}

func TestStorage_SanitizeFolderPath(t *testing.T) {
	cases := []struct {
		input    string
		expected string
	}{
		{"a/b/c", "a/b/c"},
		{"a\\b\\c", "a/b/c"},
		{"../secret/folder", "secret/folder"},
		{"one/two/three/four/five/six/seven/eight/nine/ten", "one/two/three/four/five/six/seven/eight"},
	}

	for _, c := range cases {
		out := SanitizeFolderPath(c.input)
		if out != c.expected {
			t.Errorf("SanitizeFolderPath(%q): expected %q, got %q", c.input, c.expected, out)
		}
	}
}

func TestStorage_SaveAndChunk(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "nullhost-storage-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	filePath := filepath.Join(tmpDir, "testitem_chunked.bin")

	// Chunk 0: "Hello "
	chunk0 := strings.NewReader("Hello ")
	size0, err := SaveChunk(filePath, 0, chunk0)
	if err != nil {
		t.Fatalf("chunk 0 failed: %v", err)
	}
	if size0 != 6 {
		t.Errorf("expected size 6, got %d", size0)
	}

	// Chunk 1: "World!"
	chunk1 := strings.NewReader("World!")
	size1, err := SaveChunk(filePath, 1, chunk1)
	if err != nil {
		t.Fatalf("chunk 1 failed: %v", err)
	}
	if size1 != 12 {
		t.Errorf("expected size 12, got %d", size1)
	}

	data, err := os.ReadFile(filePath)
	if err != nil {
		t.Fatalf("failed to read assembled file: %v", err)
	}
	if string(data) != "Hello World!" {
		t.Errorf("expected 'Hello World!', got %q", string(data))
	}

	// Delete file
	if err := DeleteFile(filePath); err != nil {
		t.Fatalf("failed to delete file: %v", err)
	}
	if _, err := os.Stat(filePath); !os.IsNotExist(err) {
		t.Errorf("expected file to be deleted")
	}
}

func TestStorage_StripEXIF(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "nullhost-exif-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	imgPath := filepath.Join(tmpDir, "sample.png")
	img := image.NewRGBA(image.Rect(0, 0, 10, 10))
	for x := 0; x < 10; x++ {
		for y := 0; y < 10; y++ {
			img.Set(x, y, color.RGBA{R: 255, A: 255})
		}
	}

	f, err := os.Create(imgPath)
	if err != nil {
		t.Fatalf("failed to create sample image: %v", err)
	}
	if err := png.Encode(f, img); err != nil {
		f.Close()
		t.Fatalf("failed to encode sample png: %v", err)
	}
	f.Close()

	if err := StripEXIF(imgPath); err != nil {
		t.Fatalf("StripEXIF failed: %v", err)
	}

	// Verify it still opens as a valid image
	checkFile, err := os.Open(imgPath)
	if err != nil {
		t.Fatalf("failed to open stripped image: %v", err)
	}
	defer checkFile.Close()

	decoded, format, err := image.Decode(checkFile)
	if err != nil {
		t.Fatalf("failed to decode stripped image: %v", err)
	}
	if format != "png" {
		t.Errorf("expected format png, got %s", format)
	}
	if decoded.Bounds().Dx() != 10 || decoded.Bounds().Dy() != 10 {
		t.Errorf("bounds mismatch")
	}
}

func TestStorage_StreamZipCollection(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "nullhost-zip-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	// Create a dummy file
	fPath := FilePath(tmpDir, "item1", "file.txt")
	if _, err := SaveFile(fPath, strings.NewReader("file content here")); err != nil {
		t.Fatalf("failed to save file: %v", err)
	}

	items := []CollectionZipEntry{
		{
			ItemID:     "item1",
			Filename:   "file.txt",
			FolderPath: "docs/text",
			Type:       "file",
		},
		{
			ItemID:     "item2",
			Filename:   "note.txt",
			FolderPath: "docs",
			Type:       "paste",
			Content:    "paste note content",
		},
	}

	var buf bytes.Buffer
	if err := StreamZipCollection(&buf, tmpDir, items); err != nil {
		t.Fatalf("StreamZipCollection failed: %v", err)
	}

	// Verify zip contents
	zr, err := zip.NewReader(bytes.NewReader(buf.Bytes()), int64(buf.Len()))
	if err != nil {
		t.Fatalf("failed to open zip reader: %v", err)
	}

	if len(zr.File) != 2 {
		t.Fatalf("expected 2 files in zip, got %d", len(zr.File))
	}

	foundFile := false
	foundPaste := false
	for _, zf := range zr.File {
		rc, err := zf.Open()
		if err != nil {
			t.Fatalf("failed to open zip member: %v", err)
		}
		data, _ := io.ReadAll(rc)
		rc.Close()

		if zf.Name == "docs/text/file.txt" && string(data) == "file content here" {
			foundFile = true
		}
		if zf.Name == "docs/note.txt" && string(data) == "paste note content" {
			foundPaste = true
		}
	}

	if !foundFile || !foundPaste {
		t.Errorf("missing expected files in zip archive: foundFile=%v, foundPaste=%v", foundFile, foundPaste)
	}
}
