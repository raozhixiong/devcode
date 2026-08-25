-- 龙虾共享库 V1：用户/认证/agent 注册/任务/看板/审批/调度/密钥/审计/配置
-- 依据《07-龙虾-表设计.md》第一章

CREATE TABLE user (
  id            TEXT PRIMARY KEY,
  username      TEXT NOT NULL UNIQUE,
  display_name  TEXT NOT NULL,
  email         TEXT,
  avatar_url    TEXT,
  role          TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'active',
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);

CREATE TABLE auth_token (
  id            TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  token_hash    TEXT NOT NULL,
  scopes        TEXT NOT NULL,
  created_by    TEXT REFERENCES user(id),
  created_at    INTEGER NOT NULL,
  expires_at    INTEGER,
  revoked_at    INTEGER
);

CREATE TABLE device (
  id            TEXT PRIMARY KEY,
  label         TEXT,
  role          TEXT NOT NULL,
  public_key    TEXT NOT NULL,
  platform      TEXT,
  access        TEXT NOT NULL,
  approved_at   INTEGER,
  last_seen_at  INTEGER,
  created_at    INTEGER NOT NULL
);

CREATE TABLE device_pairing_request (
  id            TEXT PRIMARY KEY,
  device_id     TEXT,
  status        TEXT NOT NULL,
  scopes        TEXT,
  created_at    INTEGER NOT NULL,
  resolved_at   INTEGER
);

CREATE TABLE agent (
  id            TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  kind          TEXT NOT NULL DEFAULT 'agent',
  role          TEXT NOT NULL,
  emoji         TEXT,
  avatar_url    TEXT,
  workspace_dir TEXT NOT NULL,
  db_path       TEXT NOT NULL,
  model_provider TEXT,
  model_id      TEXT,
  utility_model TEXT,
  permission_rules TEXT NOT NULL,
  tool_profile  TEXT,
  skills_allowlist TEXT,
  subagent_depth INTEGER NOT NULL DEFAULT 1,
  hidden        INTEGER NOT NULL DEFAULT 0,
  created_via   TEXT,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);

CREATE TABLE agent_binding (
  agent_id      TEXT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
  user_id       TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
  can_write     INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (agent_id, user_id)
);

CREATE TABLE channel_binding (
  id            TEXT PRIMARY KEY,
  channel       TEXT NOT NULL,
  account_id    TEXT NOT NULL,
  agent_id      TEXT NOT NULL REFERENCES agent(id),
  config        TEXT,
  created_at    INTEGER NOT NULL,
  UNIQUE (channel, account_id)
);

CREATE TABLE task (
  id            TEXT PRIMARY KEY,
  runtime       TEXT NOT NULL,
  task_kind     TEXT,
  source_id     TEXT,
  requester_agent_id TEXT,
  owner_key     TEXT NOT NULL,
  parent_task_id TEXT,
  agent_id      TEXT,
  run_id        TEXT,
  label         TEXT,
  task_text     TEXT NOT NULL,
  status        TEXT NOT NULL,
  delivery_status TEXT,
  notify_policy TEXT NOT NULL DEFAULT 'state_changes',
  tool_use_count INTEGER DEFAULT 0,
  last_tool_name TEXT,
  error         TEXT,
  progress_summary TEXT,
  terminal_summary TEXT,
  detail        TEXT,
  created_at    INTEGER NOT NULL,
  started_at    INTEGER,
  ended_at      INTEGER,
  last_event_at INTEGER,
  cleanup_after INTEGER
);
CREATE INDEX idx_task_owner ON task(owner_key, status);
CREATE INDEX idx_task_status ON task(status, last_event_at);

CREATE TABLE workboard_card (
  id            TEXT PRIMARY KEY,
  board_id      TEXT NOT NULL DEFAULT 'main',
  status        TEXT NOT NULL,
  priority      TEXT NOT NULL DEFAULT 'normal',
  labels        TEXT,
  title         TEXT NOT NULL,
  description   TEXT,
  assigned_agent_id TEXT REFERENCES agent(id),
  assigned_user_id  TEXT REFERENCES user(id),
  linked_task_id TEXT,
  linked_run_id  TEXT,
  linked_session_key TEXT,
  execution     TEXT,
  position      REAL NOT NULL,
  template_id   TEXT,
  archived      INTEGER NOT NULL DEFAULT 0,
  metadata      TEXT,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);
CREATE INDEX idx_card_board ON workboard_card(board_id, status, position);

CREATE TABLE workboard_event (
  id            TEXT PRIMARY KEY,
  card_id       TEXT NOT NULL REFERENCES workboard_card(id) ON DELETE CASCADE,
  kind          TEXT NOT NULL,
  actor         TEXT,
  payload       TEXT,
  created_at    INTEGER NOT NULL
);
CREATE INDEX idx_card_event ON workboard_event(card_id, created_at);

CREATE TABLE approval (
  id            TEXT PRIMARY KEY,
  kind          TEXT NOT NULL,
  session_key   TEXT,
  agent_id      TEXT,
  requester     TEXT NOT NULL,
  payload       TEXT NOT NULL,
  status        TEXT NOT NULL,
  resolver      TEXT,
  reason        TEXT,
  created_at    INTEGER NOT NULL,
  resolved_at   INTEGER
);
CREATE INDEX idx_approval_status ON approval(status, kind, created_at);

CREATE TABLE exec_approval_policy (
  scope         TEXT NOT NULL,
  policy        TEXT NOT NULL,
  updated_by    TEXT,
  updated_at    INTEGER NOT NULL,
  PRIMARY KEY (scope)
);

CREATE TABLE cron_job (
  id            TEXT PRIMARY KEY,
  agent_id      TEXT NOT NULL REFERENCES agent(id),
  name          TEXT NOT NULL,
  schedule      TEXT NOT NULL,
  prompt        TEXT NOT NULL,
  session_policy TEXT,
  enabled       INTEGER NOT NULL DEFAULT 1,
  next_fire_at  INTEGER,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);

CREATE TABLE cron_run (
  id            TEXT PRIMARY KEY,
  job_id        TEXT NOT NULL REFERENCES cron_job(id) ON DELETE CASCADE,
  fire_at       INTEGER NOT NULL,
  started_at    INTEGER,
  ended_at      INTEGER,
  status        TEXT NOT NULL,
  run_id        TEXT,
  error         TEXT,
  UNIQUE (job_id, fire_at)
);

CREATE TABLE secret_store_entry (
  id            TEXT PRIMARY KEY,
  team_scope    TEXT NOT NULL DEFAULT 'default',
  name          TEXT NOT NULL,
  kind          TEXT NOT NULL,
  value_cipher  TEXT NOT NULL,
  created_by    TEXT,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  deleted_at    INTEGER,
  UNIQUE (team_scope, name)
);

CREATE TABLE audit_event (
  id            TEXT PRIMARY KEY,
  ts            INTEGER NOT NULL,
  actor         TEXT,
  kind          TEXT NOT NULL,
  session_key   TEXT,
  agent_id      TEXT,
  result        TEXT,
  meta          TEXT
);
CREATE INDEX idx_audit_ts ON audit_event(ts);

CREATE TABLE config_state (
  path          TEXT PRIMARY KEY,
  value         TEXT NOT NULL,
  revision_hash TEXT NOT NULL,
  updated_by    TEXT,
  updated_at    INTEGER NOT NULL
);

CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);

CREATE TABLE plugin (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  source TEXT NOT NULL,
  version TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  status TEXT,
  installed_at INTEGER NOT NULL
);
