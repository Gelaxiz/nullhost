package config

import (
	"bufio"
	"os"
	"strings"
)

type Config struct {
	Host                 string
	Port                 string
	SiteDomain           string
	SiteName             string
	DmcaEmail            string
	SecretKey            string
	AdminPassword        string
	AdminTOTPSecret      string
	TurnstileSecretKey   string
	TurnstileSiteKey     string
	MobileAppKey         string
	UploadFolder         string
	DBPath               string
	BaseURL              string
}

func getEnv(key, defaultVal string) string {
	if val, ok := os.LookupEnv(key); ok && strings.TrimSpace(val) != "" {
		return strings.TrimSpace(val)
	}
	return defaultVal
}

func LoadConfig(envPath string) *Config {
	if envPath != "" {
		if file, err := os.Open(envPath); err == nil {
			defer file.Close()
			scanner := bufio.NewScanner(file)
			for scanner.Scan() {
				line := strings.TrimSpace(scanner.Text())
				if line == "" || strings.HasPrefix(line, "#") {
					continue
				}
				parts := strings.SplitN(line, "=", 2)
				if len(parts) == 2 {
					key := strings.TrimSpace(parts[0])
					val := strings.TrimSpace(parts[1])
					val = strings.Trim(val, `"'`)
					if _, exists := os.LookupEnv(key); !exists {
						os.Setenv(key, val)
					}
				}
			}
		}
	}

	siteDomain := getEnv("SITE_DOMAIN", "nullhost.gelaxiz.xyz")
	baseURL := "https://" + siteDomain
	if strings.Contains(siteDomain, "localhost") || strings.Contains(siteDomain, "127.0.0.1") {
		baseURL = "http://" + siteDomain
	}

	turnstileSecret := getEnv("TURNSTILE_SECRET", getEnv("CF_TURNSTILE_SECRET_KEY", ""))
	turnstileSiteKey := getEnv("TURNSTILE_SITE_KEY", getEnv("CF_TURNSTILE_SITE_KEY", ""))
	mobileAppKey := getEnv("NULLHOST_MOBILE_APP_KEY", "nullhost_secret_mobile_app_key_2026")

	dbPath := getEnv("DB_PATH", "nullhost.db")
	if dbPath == "nullhost.db" {
		if _, err := os.Stat("nullhost.db"); os.IsNotExist(err) {
			if _, err := os.Stat("../nullhost.db"); err == nil {
				dbPath = "../nullhost.db"
			}
		}
	}

	uploadFolder := getEnv("UPLOAD_FOLDER", "uploads")
	if uploadFolder == "uploads" {
		if _, err := os.Stat("uploads"); os.IsNotExist(err) {
			if _, err := os.Stat("../uploads"); err == nil {
				uploadFolder = "../uploads"
			}
		}
	}

	return &Config{
		Host:               getEnv("HOST", "127.0.0.1"),
		Port:               getEnv("PORT", "5005"),
		SiteDomain:         siteDomain,
		SiteName:           getEnv("SITE_NAME", "nullhost"),
		DmcaEmail:          getEnv("DMCA_EMAIL", "dmca@gelaxiz.xyz"),
		SecretKey:          getEnv("SECRET_KEY", "default-dev-secret-key-change-in-production"),
		AdminPassword:      getEnv("ADMIN_PASSWORD", "Syyskuu_05"),
		AdminTOTPSecret:    getEnv("ADMIN_TOTP_SECRET", "VTAE42SXAHBHR3EZ"),
		TurnstileSecretKey: turnstileSecret,
		TurnstileSiteKey:   turnstileSiteKey,
		MobileAppKey:       mobileAppKey,
		UploadFolder:       uploadFolder,
		DBPath:             dbPath,
		BaseURL:            baseURL,
	}
}
