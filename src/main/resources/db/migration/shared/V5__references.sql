-- M6 参考库 References（FR-I3）
CREATE TABLE ref_entries (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  kind        TEXT NOT NULL,            -- local | git | url
  uri         TEXT NOT NULL,
  description TEXT,
  enabled     INTEGER NOT NULL DEFAULT 1,
  created_at  INTEGER NOT NULL
);
