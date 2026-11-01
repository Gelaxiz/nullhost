package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"nullhost/internal/cleanup"
	"nullhost/internal/config"
	"nullhost/internal/database"
	"nullhost/internal/handlers"
)

func main() {
	log.Println("[Nullhost] Starting High-Performance Go Service...")

	// Find .env file in current dir or parent dir
	envPath := ".env"
	if _, err := os.Stat(envPath); os.IsNotExist(err) {
		parentEnv := filepath.Join("..", ".env")
		if _, err := os.Stat(parentEnv); err == nil {
			envPath = parentEnv
		} else {
			envPath = ""
		}
	}

	cfg := config.LoadConfig(envPath)
	log.Printf("[Nullhost] Configuration loaded: Host=%s, Port=%s, Domain=%s", cfg.Host, cfg.Port, cfg.SiteDomain)

	// Open SQLite database
	db, err := database.OpenDB(cfg.DBPath)
	if err != nil {
		log.Fatalf("[Nullhost] FATAL: Failed to open SQLite database at %s: %v", cfg.DBPath, err)
	}
	defer db.Close()
	log.Printf("[Nullhost] SQLite database opened with WAL mode at %s", cfg.DBPath)

	// Ensure upload directory exists
	if err := os.MkdirAll(cfg.UploadFolder, 0755); err != nil {
		log.Fatalf("[Nullhost] FATAL: Failed to create upload folder %s: %v", cfg.UploadFolder, err)
	}

	// Context for graceful shutdown
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Start background cleanup worker
	cleanup.StartCleanupWorker(ctx, db, cfg.UploadFolder, 10*time.Minute)
	log.Printf("[Nullhost] Background cleanup worker started (10 minute ticker)")

	// Create HTTP server instance
	srv := handlers.NewServer(cfg, db)

	addr := fmt.Sprintf("%s:%s", cfg.Host, cfg.Port)
	httpServer := &http.Server{
		Addr:         addr,
		Handler:      srv.Router,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 60 * time.Second,
		IdleTimeout:  120 * time.Second,
	}

	// Channel for graceful shutdown signals
	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)

	go func() {
		log.Printf("[Nullhost] HTTP Server listening on http://%s", addr)
		if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("[Nullhost] HTTP Server error: %v", err)
		}
	}()

	<-stopChan
	log.Println("[Nullhost] Shutting down gracefully...")

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()

	if err := httpServer.Shutdown(shutdownCtx); err != nil {
		log.Printf("[Nullhost] Server shutdown error: %v", err)
	}

	log.Println("[Nullhost] Server stopped.")
}
