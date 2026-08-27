-- M6 扩展内核：通用生命周期钩子框架（FR-I1）
CREATE TABLE hooks (
  id         TEXT PRIMARY KEY,
  scope      TEXT NOT NULL DEFAULT 'global',   -- global | agent | session
  scope_id   TEXT,                              -- agentId / sessionId（global 为 NULL）
  event      TEXT NOT NULL,                     -- agent.run.started / tool.before / skill.completed ...
  kind       TEXT NOT NULL,                     -- command | script | http
  command    TEXT NOT NULL,                     -- 待执行命令/脚本/URL
  enabled    INTEGER NOT NULL DEFAULT 1,
  timeout_ms INTEGER NOT NULL DEFAULT 5000,
  created_at INTEGER NOT NULL
);

CREATE TABLE hook_run (
  id         TEXT PRIMARY KEY,
  hook_id    TEXT NOT NULL,
  event      TEXT NOT NULL,
  status     TEXT NOT NULL,                     -- success | failed | blocked
  exit_code  INTEGER,
  output     TEXT,
  created_at INTEGER NOT NULL
);
