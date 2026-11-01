package templates

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"

	"github.com/nikolalohinski/gonja/v2"
	"github.com/nikolalohinski/gonja/v2/builtins"
	"github.com/nikolalohinski/gonja/v2/exec"
	"github.com/nikolalohinski/gonja/v2/loaders"
	"nullhost/internal/config"
)

var bufPool = sync.Pool{
	New: func() any {
		return new(bytes.Buffer)
	},
}

type Renderer struct {
	cfg         *config.Config
	templateDir string
	cache       sync.Map // map[string]*exec.Template
	urlForMacro exec.Macro
}

func NewRenderer(cfg *config.Config) *Renderer {
	tplDir := "templates"
	for _, cand := range []string{"templates", "../templates", "../../templates", "/home/projects/nullhost/templates"} {
		if _, err := os.Stat(filepath.Join(cand, "base.html")); err == nil {
			if abs, err := filepath.Abs(cand); err == nil {
				tplDir = abs
			} else {
				tplDir = cand
			}
			break
		}
	}

	gonja.DefaultLoader = loaders.MustNewFileSystemLoader(tplDir)
	if !builtins.Filters.Exists("tojson") {
		_ = builtins.Filters.Register("tojson", func(e *exec.Evaluator, in *exec.Value, params *exec.VarArgs) *exec.Value {
			if in == nil || in.IsNil() {
				return exec.AsSafeValue("null")
			}
			b, err := json.Marshal(in.Interface())
			if err != nil {
				return exec.AsSafeValue("null")
			}
			return exec.AsSafeValue(string(b))
		})
	}

	urlForMacro := exec.Macro(func(params *exec.VarArgs) *exec.Value {
		if len(params.Args) > 0 {
			endpoint := params.Args[0].String()
			if endpoint == "static" {
				if fnVal, ok := params.KwArgs["filename"]; ok {
					vStr := ""
					if vVal, vok := params.KwArgs["v"]; vok {
						vStr = "?v=" + vVal.String()
					}
					return exec.AsValue("/static/" + fnVal.String() + vStr)
				}
				return exec.AsValue("/static")
			}
			if endpoint == "favicon" {
				return exec.AsValue("/favicon.ico")
			}
			if endpoint == "overview" {
				if itemID, ok := params.KwArgs["item_id"]; ok {
					return exec.AsValue("/v/" + itemID.String())
				}
			}
			if endpoint == "raw" {
				if itemID, ok := params.KwArgs["item_id"]; ok {
					if fn, ok := params.KwArgs["filename"]; ok {
						return exec.AsValue("/raw/" + itemID.String() + "/" + fn.String())
					}
					return exec.AsValue("/raw/" + itemID.String())
				}
			}
			if endpoint == "download" {
				if itemID, ok := params.KwArgs["item_id"]; ok {
					if fn, ok := params.KwArgs["filename"]; ok {
						return exec.AsValue("/download/" + itemID.String() + "/" + fn.String())
					}
					return exec.AsValue("/dl/" + itemID.String())
				}
			}
			if endpoint == "collection_view" {
				if colID, ok := params.KwArgs["collection_id"]; ok {
					return exec.AsValue("/c/" + colID.String())
				}
			}
			if endpoint == "account" {
				return exec.AsValue("/a")
			}
			return exec.AsValue("/" + endpoint)
		}
		return exec.AsValue("")
	})

	return &Renderer{
		cfg:         cfg,
		templateDir: tplDir,
		urlForMacro: urlForMacro,
	}
}

// Render executes a Jinja2 template and writes the result to w
func (r *Renderer) Render(w io.Writer, templateName string, data map[string]any) error {
	var tpl *exec.Template
	if val, ok := r.cache.Load(templateName); ok {
		tpl = val.(*exec.Template)
	} else {
		parsed, err := gonja.FromFile(templateName)
		if err != nil {
			fullPath := filepath.Join(r.templateDir, templateName)
			parsed, err = gonja.FromFile(fullPath)
			if err != nil {
				return fmt.Errorf("failed to parse template %s: %w", templateName, err)
			}
		}
		r.cache.Store(templateName, parsed)
		tpl = parsed
	}

	merged := map[string]any{
		"url_for":            r.urlForMacro,
		"site_domain":        r.cfg.SiteDomain,
		"site_name":          r.cfg.SiteName,
		"turnstile_enabled":  r.cfg.TurnstileSiteKey != "",
		"turnstile_site_key": r.cfg.TurnstileSiteKey,
		"turnstile_action":   "turnstile-spin-v2",
		"legacy_mode":        false,
		"logged_in":          false,
	}

	for k, v := range data {
		merged[k] = v
	}

	ctx := exec.NewContext(merged)
	buf := bufPool.Get().(*bytes.Buffer)
	buf.Reset()
	defer bufPool.Put(buf)

	if err := tpl.Execute(buf, ctx); err != nil {
		return fmt.Errorf("failed to execute template %s: %w", templateName, err)
	}

	_, err := io.Copy(w, buf)
	return err
}
