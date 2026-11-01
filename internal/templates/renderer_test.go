package templates

import (
	"bytes"
	"testing"

	"nullhost/internal/config"
)

func TestRenderer_AdminDashboard(t *testing.T) {
	cfg := &config.Config{
		SiteDomain: "nullhost.gelaxiz.xyz",
		SiteName:   "nullhost",
	}
	r := NewRenderer(cfg)

	var buf bytes.Buffer
	err := r.Render(&buf, "admin_dashboard.html", map[string]any{
		"just_logged_in": true,
		"admin_csrf":     "test-csrf",
		"items":          []any{},
		"accounts":       []any{},
		"tombstones":     []any{},
		"stats":          map[string]any{},
		"now":            123456789,
	})
	if err != nil {
		t.Fatalf("Failed to render admin_dashboard.html: %v", err)
	}

	rendered := buf.String()
	t.Logf("Rendered admin_dashboard.html length: %d", len(rendered))
	idx := bytes.Index(buf.Bytes(), []byte("var justLoggedIn"))
	if idx != -1 {
		end := idx + 150
		if end > len(rendered) {
			end = len(rendered)
		}
		t.Logf("Snippet: %s", rendered[idx:end])
	}
}
