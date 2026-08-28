-- M6 集成/OAuth 框架（FR-I4）
CREATE TABLE integrations (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  kind        TEXT NOT NULL,            -- oauth | key | env
  status      TEXT NOT NULL,            -- connecting | awaiting | connected | error
  config_json TEXT,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);

CREATE TABLE integration_attempts (
  id            TEXT PRIMARY KEY,
  integration_id TEXT NOT NULL,
  status        TEXT NOT NULL,          -- awaiting | completed | error | cancelled
  step          TEXT,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);
