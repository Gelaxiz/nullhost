package auth

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base32"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/pbkdf2"
	"golang.org/x/crypto/scrypt"
)

const MobileAppKey = "nullhost_secret_mobile_app_key_2026"

type AdminManager struct {
	adminPassword  string
	totpSecret     string
	turnstileKey   string
	secretKey      []byte
	failedAttempts sync.Map // map[string]int64 (ip -> last failed timestamp)
}

func NewAdminManager(adminPassword, totpSecret, turnstileKey, secretKey string) *AdminManager {
	return &AdminManager{
		adminPassword: adminPassword,
		totpSecret:    strings.TrimSpace(totpSecret),
		turnstileKey:  turnstileKey,
		secretKey:     []byte(secretKey),
	}
}

// CheckCooldown checks if an IP is under rate limit cooldown (10s)
func (am *AdminManager) CheckCooldown(ip string) (bool, int) {
	val, ok := am.failedAttempts.Load(ip)
	if !ok {
		return false, 0
	}
	lastTime := val.(int64)
	elapsed := time.Now().Unix() - lastTime
	if elapsed < 10 {
		return true, int(10 - elapsed)
	}
	am.failedAttempts.Delete(ip)
	return false, 0
}

func (am *AdminManager) RecordFailure(ip string) {
	am.failedAttempts.Store(ip, time.Now().Unix())
}

func (am *AdminManager) ClearFailure(ip string) {
	am.failedAttempts.Delete(ip)
}

// VerifyPassword checks admin password with constant-time comparison
func (am *AdminManager) VerifyPassword(provided string) bool {
	if am.adminPassword == "" {
		return true
	}
	providedBytes := []byte(provided)
	expectedBytes := []byte(am.adminPassword)
	if len(providedBytes) != len(expectedBytes) {
		return false
	}
	return subtle.ConstantTimeCompare(providedBytes, expectedBytes) == 1
}

// VerifyTOTP verifies a 6-digit TOTP token against the base32 secret (window ±2 steps)
func (am *AdminManager) VerifyTOTP(tokenStr string) bool {
	return am.VerifyTOTPWithWindow(tokenStr, 2)
}

// VerifyTOTPWithWindow verifies a 6-digit TOTP token against base32 secret with custom step window
func (am *AdminManager) VerifyTOTPWithWindow(tokenStr string, window int) bool {
	tokenStr = strings.TrimSpace(tokenStr)
	if len(tokenStr) != 6 {
		return false
	}
	expectedCode, err := strconv.Atoi(tokenStr)
	if err != nil {
		return false
	}

	secretKeyClean := strings.ToUpper(strings.ReplaceAll(am.totpSecret, " ", ""))
	key, err := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(secretKeyClean)
	if err != nil {
		// try with std padding
		key, err = base32.StdEncoding.DecodeString(secretKeyClean)
		if err != nil {
			return false
		}
	}

	if window < 1 {
		window = 2
	}

	currentStep := time.Now().Unix() / 30
	// Check current step and adjacent steps (window = ±window)
	for offset := int64(-window); offset <= int64(window); offset++ {
		step := currentStep + offset
		if computeHOTP(key, step) == expectedCode {
			return true
		}
	}
	return false
}

func computeHOTP(key []byte, counter int64) int {
	buf := make([]byte, 8)
	binary.BigEndian.PutUint64(buf, uint64(counter))

	mac := hmac.New(sha1.New, key)
	mac.Write(buf)
	hash := mac.Sum(nil)

	offset := hash[len(hash)-1] & 0x0f
	truncatedHash := binary.BigEndian.Uint32(hash[offset:offset+4]) & 0x7fffffff
	return int(truncatedHash % 1000000)
}

// VerifyTurnstile verifies Cloudflare Turnstile token
func (am *AdminManager) VerifyTurnstile(ctx context.Context, token, remoteIP string) (bool, error) {
	if am.turnstileKey == "" {
		return true, nil // Disabled if no key configured
	}
	if token == "" {
		return false, nil
	}

	form := url.Values{}
	form.Set("secret", am.turnstileKey)
	form.Set("response", token)
	if remoteIP != "" {
		form.Set("remoteip", remoteIP)
	}

	req, err := http.NewRequestWithContext(ctx, "POST", "https://challenges.cloudflare.com/turnstile/v0/siteverify", strings.NewReader(form.Encode()))
	if err != nil {
		return false, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()

	var result struct {
		Success bool `json:"success"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return false, err
	}
	return result.Success, nil
}

// CreateSessionToken generates a signed memory-only session token
func (am *AdminManager) CreateSessionToken(expirySeconds int64) string {
	expiresAt := time.Now().Unix() + expirySeconds
	randomBytes := make([]byte, 16)
	io.ReadFull(rand.Reader, randomBytes)
	nonce := hex.EncodeToString(randomBytes)

	payload := fmt.Sprintf("%d.%s", expiresAt, nonce)
	h := hmac.New(sha256.New, am.secretKey)
	h.Write([]byte(payload))
	signature := hex.EncodeToString(h.Sum(nil))

	return fmt.Sprintf("%s.%s", payload, signature)
}

// ValidateSessionToken verifies a signed session token
func (am *AdminManager) ValidateSessionToken(token string) bool {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return false
	}
	expiresAt, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil || time.Now().Unix() > expiresAt {
		return false
	}

	payload := fmt.Sprintf("%s.%s", parts[0], parts[1])
	h := hmac.New(sha256.New, am.secretKey)
	h.Write([]byte(payload))
	expectedSignature := hex.EncodeToString(h.Sum(nil))

	return subtle.ConstantTimeCompare([]byte(parts[2]), []byte(expectedSignature)) == 1
}

// HashPassword hashes a user/collection password (Werkzeug pbkdf2:sha256 compatible)
func HashPassword(password string) string {
	salt := make([]byte, 16)
	io.ReadFull(rand.Reader, salt)
	saltHex := hex.EncodeToString(salt)

	iterations := 260000
	hash := pbkdf2.Key([]byte(password), []byte(saltHex), iterations, 32, sha256.New)
	hashHex := hex.EncodeToString(hash)

	return fmt.Sprintf("pbkdf2:sha256:%d$%s$%s", iterations, saltHex, hashHex)
}

// CheckPassword verifies password against Werkzeug pbkdf2, scrypt, or raw sha256
func CheckPassword(hashedPassword, password string) bool {
	if hashedPassword == "" || password == "" {
		return false
	}

	// 1. Werkzeug PBKDF2: pbkdf2:sha256:iterations$salt$hash
	if strings.HasPrefix(hashedPassword, "pbkdf2:sha256:") {
		parts := strings.Split(hashedPassword, "$")
		if len(parts) != 3 {
			return false
		}
		prefixParts := strings.Split(parts[0], ":")
		if len(prefixParts) != 3 {
			return false
		}
		iterations, err := strconv.Atoi(prefixParts[2])
		if err != nil {
			return false
		}
		salt := parts[1]
		expectedHash := parts[2]

		calculated := pbkdf2.Key([]byte(password), []byte(salt), iterations, len(expectedHash)/2, sha256.New)
		calculatedHex := hex.EncodeToString(calculated)

		return subtle.ConstantTimeCompare([]byte(calculatedHex), []byte(expectedHash)) == 1
	}

	// 2. Werkzeug Scrypt: scrypt:32768:8:1$salt$hash
	if strings.HasPrefix(hashedPassword, "scrypt:") {
		parts := strings.Split(hashedPassword, "$")
		if len(parts) != 3 {
			return false
		}
		prefixParts := strings.Split(parts[0], ":")
		if len(prefixParts) < 4 {
			return false
		}
		n, _ := strconv.Atoi(prefixParts[1])
		r, _ := strconv.Atoi(prefixParts[2])
		p, _ := strconv.Atoi(prefixParts[3])
		salt := parts[1]
		expectedHash := parts[2]

		calculated, err := scrypt.Key([]byte(password), []byte(salt), n, r, p, len(expectedHash)/2)
		if err != nil {
			return false
		}
		calculatedHex := hex.EncodeToString(calculated)
		return subtle.ConstantTimeCompare([]byte(calculatedHex), []byte(expectedHash)) == 1
	}

	// 3. Fallback raw sha256
	h := sha256.Sum256([]byte(password))
	hHex := hex.EncodeToString(h[:])
	return subtle.ConstantTimeCompare([]byte(hashedPassword), []byte(hHex)) == 1
}

// GenerateNumericID produces 16 or 24-digit account number
func GenerateNumericID(digits int) (string, error) {
	if digits != 16 && digits != 24 {
		digits = 16
	}
	var sb strings.Builder
	firstDigit, err := rand.Int(rand.Reader, bigInt(9))
	if err != nil {
		return "", err
	}
	sb.WriteString(strconv.Itoa(int(firstDigit.Int64() + 1)))

	for i := 1; i < digits; i++ {
		d, err := rand.Int(rand.Reader, bigInt(10))
		if err != nil {
			return "", err
		}
		sb.WriteString(strconv.Itoa(int(d.Int64())))
	}
	return sb.String(), nil
}

// GenerateNumericAccountID is an alias for GenerateNumericID
func GenerateNumericAccountID(digits int) (string, error) {
	return GenerateNumericID(digits)
}

// VerifyPassword checks if a plain password matches a Werkzeug/scrypt/pbkdf2 hash
func VerifyPassword(password, hashedPassword string) (bool, error) {
	if hashedPassword == "" {
		return false, nil
	}
	return CheckPassword(hashedPassword, password), nil
}

// GenerateTOTP generates an RFC 6238 TOTP code for testing or authenticator registration
func GenerateTOTP(secret string, timestamp int64, digits int) (string, error) {
	cleanSecret := strings.ToUpper(strings.ReplaceAll(secret, " ", ""))
	key, err := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(cleanSecret)
	if err != nil {
		key, err = base32.StdEncoding.DecodeString(cleanSecret)
		if err != nil {
			return "", err
		}
	}
	step := timestamp / 30
	code := computeHOTP(key, step)
	format := fmt.Sprintf("%%0%dd", digits)
	return fmt.Sprintf(format, code), nil
}

// VerifyTOTP verifies a TOTP code against a base32 secret
func VerifyTOTP(secret, tokenStr string, window int) bool {
	am := NewAdminManager("", secret, "", "")
	return am.VerifyTOTPWithWindow(tokenStr, window)
}

func bigInt(val int64) *big.Int {
	return mathBigInt(val)
}

func mathBigInt(v int64) *big.Int {
	return big.NewInt(v)
}
