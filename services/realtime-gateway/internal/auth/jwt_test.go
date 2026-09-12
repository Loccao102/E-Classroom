package auth

import (
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

func TestVerifyHS256(t *testing.T) {
	secret := "01234567890123456789012345678901"
	claims := Claims{
		Email: "a@example.com",
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer:    "eclassroom",
			Subject:   "user-1",
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Minute)),
		},
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	raw, err := token.SignedString([]byte(secret))
	if err != nil {
		t.Fatal(err)
	}
	got, err := Verify(raw, secret, "eclassroom")
	if err != nil {
		t.Fatal(err)
	}
	if got.Subject != "user-1" {
		t.Fatalf("subject=%s", got.Subject)
	}
}

func TestRejectWrongIssuer(t *testing.T) {
	secret := "01234567890123456789012345678901"
	claims := Claims{RegisteredClaims: jwt.RegisteredClaims{
		Issuer:    "wrong",
		Subject:   "u",
		ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Minute)),
	}}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	raw, _ := token.SignedString([]byte(secret))
	if _, err := Verify(raw, secret, "eclassroom"); err == nil {
		t.Fatal("expected validation error")
	}
}
