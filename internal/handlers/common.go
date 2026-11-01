package handlers

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"nullhost/internal/config"
	"nullhost/internal/database"
	"nullhost/internal/templates"
)

type Server struct {
	DB        *database.DB
	Config    *config.Config
	UploadDir string
	Renderer  *templates.Renderer
	Router    *chi.Mux
}

type SessionData struct {
	AccountID string `json:"account_id,omitempty"`
	IsAdmin   bool   `json:"is_admin,omitempty"`
	AuthTime  int64  `json:"auth_time,omitempty"`
}

func NewServer(cfg *config.Config, db *database.DB) *Server {
	s := &Server{
		DB:        db,
		Config:    cfg,
		UploadDir: cfg.UploadFolder,
		Renderer:  templates.NewRenderer(cfg),
		Router:    chi.NewRouter(),
	}
	s.routes()
	return s
}

// RenderHTML renders a Jinja2 template and writes HTML to response
func (s *Server) RenderHTML(w http.ResponseWriter, status int, templateName string, data map[string]any) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(status)
	if err := s.Renderer.Render(w, templateName, data); err != nil {
		fmt.Fprintf(w, "<!-- Template render error: %v -->", err)
	}
}

// JSON writes a JSON response with status code
func (s *Server) JSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// ReadJSON reads and decodes JSON from request body into dest
func (s *Server) ReadJSON(r *http.Request, dest any) error {
	defer r.Body.Close()
	return json.NewDecoder(r.Body).Decode(dest)
}

// Error writes a plain text error
func (s *Server) Error(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(status)
	_, _ = fmt.Fprintln(w, msg)
}

// GetClientIP gets real IP from headers or RemoteAddr
func (s *Server) GetClientIP(r *http.Request) string {
	if cf := strings.TrimSpace(r.Header.Get("CF-Connecting-IP")); cf != "" {
		return cf
	}
	if xff := strings.TrimSpace(r.Header.Get("X-Forwarded-For")); xff != "" {
		parts := strings.Split(xff, ",")
		if len(parts) > 0 && strings.TrimSpace(parts[0]) != "" {
			return strings.TrimSpace(parts[0])
		}
	}
	if xri := strings.TrimSpace(r.Header.Get("X-Real-IP")); xri != "" {
		return xri
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}
	return r.RemoteAddr
}

// GetSession extracts and verifies the HMAC signed session cookie
func (s *Server) GetSession(r *http.Request) *SessionData {
	cookie, err := r.Cookie("nullhost_session")
	if err != nil || cookie.Value == "" {
		return &SessionData{}
	}

	parts := strings.Split(cookie.Value, ".")
	if len(parts) != 2 {
		return &SessionData{}
	}

	payloadB64, sigB64 := parts[0], parts[1]
	expectedSig := hmac.New(sha256.New, []byte(s.Config.SecretKey))
	expectedSig.Write([]byte(payloadB64))
	expectedSigBytes := expectedSig.Sum(nil)

	sigBytes, err := base64.RawURLEncoding.DecodeString(sigB64)
	if err != nil || !hmac.Equal(sigBytes, expectedSigBytes) {
		return &SessionData{}
	}

	payloadBytes, err := base64.RawURLEncoding.DecodeString(payloadB64)
	if err != nil {
		return &SessionData{}
	}

	var sess SessionData
	if err := json.Unmarshal(payloadBytes, &sess); err != nil {
		return &SessionData{}
	}

	return &sess
}

// SetSession signs and saves the session data into an HTTP-only cookie
func (s *Server) SetSession(w http.ResponseWriter, r *http.Request, sess *SessionData) {
	data, _ := json.Marshal(sess)
	payloadB64 := base64.RawURLEncoding.EncodeToString(data)

	h := hmac.New(sha256.New, []byte(s.Config.SecretKey))
	h.Write([]byte(payloadB64))
	sigB64 := base64.RawURLEncoding.EncodeToString(h.Sum(nil))

	cookieVal := fmt.Sprintf("%s.%s", payloadB64, sigB64)
	http.SetCookie(w, &http.Cookie{
		Name:     "nullhost_session",
		Value:    cookieVal,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   30 * 86400,
	})
}

// ClearSession clears the session cookie
func (s *Server) ClearSession(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name:     "nullhost_session",
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		MaxAge:   -1,
	})
}

// CalculateExpiry computes the Unix timestamp when an item expires
func (s *Server) CalculateExpiry(expiryStr, customDaysStr, accountID string, now int64) int64 {
	const (
		HourSecs  = 3600
		DaySecs   = 86400
		WeekSecs  = 7 * DaySecs
		MonthSecs = 30 * DaySecs
		MaxCap    = 180 * DaySecs
	)

	if accountID != "" && customDaysStr != "" {
		days, err := strconv.ParseInt(customDaysStr, 10, 64)
		if err == nil && days > 0 {
			if days > 180 {
				days = 180
			}
			return now + (days * DaySecs)
		}
	}

	switch expiryStr {
	case "3600", "1h":
		return now + HourSecs
	case "86400", "1d":
		return now + DaySecs
	case "604800", "7d":
		return now + WeekSecs
	case "2592000", "30d":
		return now + MonthSecs
	case "never", "inf":
		if accountID != "" {
			return 2147483647 // 2038 / max 32-bit int
		}
		return now + DaySecs
	default:
		secs, err := strconv.ParseInt(expiryStr, 10, 64)
		if err == nil && secs > 0 {
			if secs > MaxCap {
				secs = MaxCap
			}
			return now + secs
		}
		return now + DaySecs
	}
}

// AuthenticateMobile verifies mobile J2ME/Symbian authentication header or token
func (s *Server) AuthenticateMobile(r *http.Request) bool {
	token := r.Header.Get("X-Nullhost-Mobile-Token")
	if token == "" {
		token = r.URL.Query().Get("mobile_token")
	}
	if token == "" {
		return false
	}

	parts := strings.Split(token, ":")
	if len(parts) != 4 {
		return false
	}

	client, tsStr, nonce, sig := parts[0], parts[1], parts[2], parts[3]
	ts, err := strconv.ParseInt(tsStr, 10, 64)
	if err != nil || time.Now().Unix()-ts > 86400 {
		return false
	}

	payload := fmt.Sprintf("%s:%s:%s", client, tsStr, nonce)
	h := hmac.New(sha256.New, []byte(s.Config.MobileAppKey))
	h.Write([]byte(payload))
	expectedSig := fmt.Sprintf("%x", h.Sum(nil))

	return hmac.Equal([]byte(sig), []byte(expectedSig))
}

var legacyBrowserMarkers = []string{
	"nokiabrowser", "nokia", "symbian", "series40", "series60",
	"s40ovibrowser", "midp", "cldc", "opera mini", "operamini",
	"belle", "meego", "maemo", "symbos",
}

// IsLegacyBrowser detects whether the client is a vintage mobile phone (Nokia, Symbian, Opera Mini)
func (s *Server) IsLegacyBrowser(r *http.Request) bool {
	if strings.HasPrefix(r.URL.Path, "/lite") {
		return true
	}
	ref := strings.ToLower(r.Header.Get("Referer"))
	if strings.Contains(ref, "/lite") {
		return true
	}
	override := r.URL.Query().Get("lite")
	if override == "1" {
		return true
	}
	if override == "0" {
		return false
	}
	ua := strings.ToLower(r.Header.Get("User-Agent"))
	if strings.Contains(ua, "android") {
		return false
	}
	for _, m := range legacyBrowserMarkers {
		if strings.Contains(ua, m) {
			return true
		}
	}
	return false
}
