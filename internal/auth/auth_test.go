package auth

import (
	"testing"
	"time"
)

func TestAuth_PasswordHashingAndVerification(t *testing.T) {
	pwd := "MySecretPass_2026!"
	hash := HashPassword(pwd)

	if !CheckPassword(hash, pwd) {
		t.Errorf("expected password verification to succeed")
	}

	if CheckPassword(hash, "wrong-password") {
		t.Errorf("expected wrong password to fail")
	}
}

func TestAuth_AdminPasswordAndCooldown(t *testing.T) {
	am := NewAdminManager("Syyskuu_05", "JBSWY3DPEHPK3PXP", "", "test-secret-key-32-chars-long12345")

	if !am.VerifyPassword("Syyskuu_05") {
		t.Errorf("expected admin password to match")
	}

	if am.VerifyPassword("wrong_pass") {
		t.Errorf("expected wrong admin password to fail")
	}

	ip := "127.0.0.1"
	isCool, _ := am.CheckCooldown(ip)
	if isCool {
		t.Errorf("should not be on cooldown initially")
	}

	am.RecordFailure(ip)
	isCool2, remaining := am.CheckCooldown(ip)
	if !isCool2 || remaining <= 0 {
		t.Errorf("expected cooldown active after failure")
	}

	am.ClearFailure(ip)
	isCool3, _ := am.CheckCooldown(ip)
	if isCool3 {
		t.Errorf("expected cooldown cleared")
	}
}

func TestAuth_SessionTokenSignAndValidate(t *testing.T) {
	am := NewAdminManager("Syyskuu_05", "JBSWY3DPEHPK3PXP", "", "my-secret-key-123456789012345678")
	token := am.CreateSessionToken(3600)

	if !am.ValidateSessionToken(token) {
		t.Errorf("valid session token failed validation")
	}

	// Tampered token
	tampered := token + "tampered"
	if am.ValidateSessionToken(tampered) {
		t.Errorf("tampered session token passed validation")
	}

	// Expired token
	expiredToken := am.CreateSessionToken(-10)
	if am.ValidateSessionToken(expiredToken) {
		t.Errorf("expired session token passed validation")
	}
}

func TestAuth_NumericIDGenerator(t *testing.T) {
	id16, err := GenerateNumericID(16)
	if err != nil {
		t.Fatalf("error generating 16-digit id: %v", err)
	}
	if len(id16) != 16 {
		t.Errorf("expected length 16, got %d (%s)", len(id16), id16)
	}

	id24, err := GenerateNumericID(24)
	if err != nil {
		t.Fatalf("error generating 24-digit id: %v", err)
	}
	if len(id24) != 24 {
		t.Errorf("expected length 24, got %d (%s)", len(id24), id24)
	}
}

func TestAuth_TOTPValidation(t *testing.T) {
	secret := "VTAE42SXAHBHR3EZ"
	now := time.Now().Unix()
	code, err := GenerateTOTP(secret, now, 6)
	if err != nil {
		t.Fatalf("GenerateTOTP failed: %v", err)
	}
	t.Logf("Live TOTP code for %s at %d: %s", secret, now, code)

	if !VerifyTOTP(secret, code, 1) {
		t.Errorf("VerifyTOTP failed for valid code %s", code)
	}

	if VerifyTOTP(secret, "000000", 1) && code != "000000" {
		t.Errorf("VerifyTOTP should fail for 000000")
	}
}
