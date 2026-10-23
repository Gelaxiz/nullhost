import sqlite3
import os
from contextlib import contextmanager

DB_PATH = os.path.join(os.path.dirname(__file__), 'nullhost.db')

@contextmanager
def get_db():
    conn = sqlite3.connect(DB_PATH, timeout=30.0)
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA foreign_keys = ON')
    try:
        with conn:
            yield conn
    finally:
        conn.close()

def init_db():
    with get_db() as conn:
        cursor = conn.cursor()
        
        # Accounts table
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS accounts (
                account_id TEXT PRIMARY KEY,
                created_at INTEGER
            )
        ''')
        
        # Items table (for both files and pastes)
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS items (
                id TEXT PRIMARY KEY,
                type TEXT NOT NULL, -- 'file' or 'paste'
                account_id TEXT, -- NULL means anonymous
                filename TEXT,
                content TEXT,
                password_hash TEXT,
                burn_after_read INTEGER DEFAULT 0,
                is_public INTEGER DEFAULT 0,
                expires_at INTEGER NOT NULL,
                dynamic_expiry INTEGER NOT NULL DEFAULT 0,
                views INTEGER DEFAULT 0,
                size INTEGER,
                mimetype TEXT,
                created_at INTEGER NOT NULL,
                is_partial INTEGER NOT NULL DEFAULT 0,
                delete_token_hash TEXT,
                FOREIGN KEY (account_id) REFERENCES accounts(account_id)
            )
        ''')
        
        # Tombstones table (for 7-day takedowns / deleted screen)
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS tombstones (
                id TEXT PRIMARY KEY,
                reason TEXT,
                deleted_at INTEGER,
                expires_at INTEGER
            )
        ''')

        # Auth nonces table (for multi-worker replay prevention)
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS auth_nonces (
                nonce TEXT PRIMARY KEY,
                expires_at INTEGER NOT NULL
            )
        ''')

        cursor.execute('''
            CREATE TABLE IF NOT EXISTS collections (
                id TEXT PRIMARY KEY,
                account_id TEXT NOT NULL,
                name TEXT NOT NULL,
                password_hash TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
            )
        ''')

        cursor.execute('''
            CREATE TABLE IF NOT EXISTS collection_items (
                collection_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                folder_path TEXT NOT NULL DEFAULT '',
                display_name TEXT,
                added_at INTEGER NOT NULL,
                PRIMARY KEY (collection_id, item_id),
                FOREIGN KEY (collection_id) REFERENCES collections(id) ON DELETE CASCADE,
                FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE
            )
        ''')

        cursor.execute('CREATE INDEX IF NOT EXISTS idx_collections_account ON collections(account_id)')
        cursor.execute('CREATE INDEX IF NOT EXISTS idx_collection_items_item ON collection_items(item_id)')

        collection_columns = {row[1] for row in cursor.execute('PRAGMA table_info(collections)').fetchall()}
        if 'password_hash' not in collection_columns:
            cursor.execute('ALTER TABLE collections ADD COLUMN password_hash TEXT')

        collection_item_columns = {row[1] for row in cursor.execute('PRAGMA table_info(collection_items)').fetchall()}
        if 'display_name' not in collection_item_columns:
            cursor.execute('ALTER TABLE collection_items ADD COLUMN display_name TEXT')

        item_columns = {row[1] for row in cursor.execute('PRAGMA table_info(items)').fetchall()}
        if 'dynamic_expiry' not in item_columns:
            cursor.execute('ALTER TABLE items ADD COLUMN dynamic_expiry INTEGER NOT NULL DEFAULT 0')
        if 'is_partial' not in item_columns:
            cursor.execute('ALTER TABLE items ADD COLUMN is_partial INTEGER NOT NULL DEFAULT 0')
        if 'delete_token_hash' not in item_columns:
            cursor.execute('ALTER TABLE items ADD COLUMN delete_token_hash TEXT')
        
        conn.commit()

if __name__ == '__main__':
    init_db()
    print("database initialized.")
