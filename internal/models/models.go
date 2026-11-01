package models

type Item struct {
	ID              string `json:"id"`
	Type            string `json:"type"` // "file" or "paste"
	AccountID       string `json:"account_id,omitempty"`
	Filename        string `json:"filename"`
	Content         string `json:"content,omitempty"`
	PasswordHash    string `json:"-"`
	BurnAfterRead   int64  `json:"burn_after_read"` // 0 = disabled, >0 = max views
	ExpiresAt       int64  `json:"expires_at"`
	Views           int64  `json:"views"`
	Size            int64  `json:"size"`
	MimeType        string `json:"mimetype"`
	CreatedAt       int64  `json:"created_at"`
	IsPublic        int    `json:"is_public"`
	DynamicExpiry   int    `json:"dynamic_expiry"`
	IsPartial       int    `json:"is_partial"`
	DeleteTokenHash string `json:"-"`
}

type Account struct {
	AccountID string `json:"account_id"`
	CreatedAt int64  `json:"created_at"`
}

type Collection struct {
	ID           string `json:"id"`
	AccountID    string `json:"account_id"`
	Name         string `json:"name"`
	PasswordHash string `json:"-"`
	CreatedAt    int64  `json:"created_at"`
}

type CollectionItem struct {
	CollectionID string `json:"collection_id"`
	ItemID       string `json:"item_id"`
	FolderPath   string `json:"folder_path"`
	AddedAt      int64  `json:"added_at"`
	DisplayName  string `json:"display_name,omitempty"`
}

type Tombstone struct {
	ID        string `json:"id"`
	Reason    string `json:"reason"`
	DeletedAt int64  `json:"deleted_at"`
	ExpiresAt int64  `json:"expires_at"`
}

type RetiredID struct {
	ID string `json:"id"`
}
