-- M6 分享链接 Share（FR-I7）
CREATE TABLE shares (
  id         TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  token      TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER
);
