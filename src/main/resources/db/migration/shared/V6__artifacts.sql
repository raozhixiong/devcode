-- M6 Artifacts（FR-I6）
CREATE TABLE artifacts (
  id         TEXT PRIMARY KEY,
  session_id TEXT,
  agent_id   TEXT,
  kind       TEXT NOT NULL,            -- generated | image | file | link
  name       TEXT NOT NULL,
  path       TEXT,
  mime       TEXT,
  created_at INTEGER NOT NULL
);
