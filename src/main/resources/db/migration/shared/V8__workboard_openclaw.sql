-- 龙虾共享库 V2：看板对齐 OpenClaw workboard-contract（15 表模型）
-- 扩展 workboard_card 并新增 14 张表：boards/links/attempts/comments/proof/
-- artifacts/diagnostics/attachments/blobs/worker_logs/worker_protocol/
-- notification_subscriptions/notifications

-- 1) 扩展现有 workboard_card
ALTER TABLE workboard_card ADD COLUMN execution_status TEXT;
ALTER TABLE workboard_card ADD COLUMN claim_token       TEXT;
ALTER TABLE workboard_card ADD COLUMN claim_owner       TEXT;
ALTER TABLE workboard_card ADD COLUMN claim_expires_at  INTEGER;
ALTER TABLE workboard_card ADD COLUMN failure_count     INTEGER NOT NULL DEFAULT 0;
ALTER TABLE workboard_card ADD COLUMN notes             TEXT;
ALTER TABLE workboard_card ADD COLUMN started_at        INTEGER;
ALTER TABLE workboard_card ADD COLUMN completed_at      INTEGER;
ALTER TABLE workboard_card ADD COLUMN source_url        TEXT;
ALTER TABLE workboard_card ADD COLUMN stale_json        TEXT;

-- 2) 多看板元数据
CREATE TABLE workboard_boards (
  id            TEXT PRIMARY KEY,
  name          TEXT,
  description   TEXT,
  icon          TEXT,
  color         TEXT,
  default_workspace_json TEXT,
  orchestration_json    TEXT,
  automation_job_id     TEXT,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  archived_at   INTEGER
);
INSERT INTO workboard_boards(id, name, created_at, updated_at)
  VALUES('main', '主看板',
    COALESCE((SELECT MAX(created_at) FROM workboard_card), (strftime('%s','now')*1000)),
    COALESCE((SELECT MAX(updated_at) FROM workboard_card), (strftime('%s','now')*1000)));
CREATE INDEX idx_board_archived ON workboard_boards(archived_at);

-- 3) 卡片标签（多对多，对齐 contract labels:string[]）
CREATE TABLE workboard_card_labels (
  card_id   TEXT NOT NULL REFERENCES workboard_card(id) ON DELETE CASCADE,
  label     TEXT NOT NULL,
  PRIMARY KEY (card_id, label)
);
CREATE INDEX idx_card_label ON workboard_card_labels(label);

-- 4) 依赖链（parent/child/blocks/blocked_by/relates_to）
CREATE TABLE workboard_card_links (
  id              TEXT PRIMARY KEY,
  card_id         TEXT NOT NULL REFERENCES workboard_card(id) ON DELETE CASCADE,
  type            TEXT NOT NULL,
  target_card_id  TEXT,
  title           TEXT,
  url             TEXT,
  created_at      INTEGER NOT NULL
);
CREATE INDEX idx_card_link_src ON workboard_card_links(card_id);
CREATE INDEX idx_card_link_dst ON workboard_card_links(target_card_id);

-- 5) 运行历史（execution attempts）
CREATE TABLE workboard_card_attempts (
  id          TEXT PRIMARY KEY,
  card_id     TEXT NOT NULL REFERENCES workboard_card(id) ON DELETE CASCADE,
  status      TEXT NOT NULL,
  started_at  INTEGER NOT NULL,
  ended_at    INTEGER,
  engine      TEXT,
  mode        TEXT,
  model       TEXT,
  session_key TEXT,
  run_id      TEXT,
  error       TEXT
);
CREATE INDEX idx_card_attempt ON workboard_card_attempts(card_id, started_at);

-- 6) 评论
CREATE TABLE workboard_card_comments (
  id          TEXT PRIMARY KEY,
  card_id     TEXT NOT NULL REFERENCES workboard_card(id) ON DELETE CASCADE,
  body        TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER
);
CREATE INDEX idx_card_comment ON workboard_card_comments(card_id, created_at);

-- 7) 证明（测试/检查通过）
CREATE TABLE workboard_card_proof (
  id          TEXT PRIMARY KEY,
  card_id     TEXT NOT NULL REFERENCES workboard_card(id) ON DELETE CASCADE,
  status      TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  label       TEXT,
  command     TEXT,
  url         TEXT,
  note        TEXT
);
CREATE INDEX idx_card_proof ON workboard_card_proof(card_id, created_at);

-- 8) 产物引用
CREATE TABLE workboard_card_artifacts (
  id          TEXT PRIMARY KEY,
  card_id     TEXT NOT NULL REFERENCES workboard_card(id) ON DELETE CASCADE,
  created_at  INTEGER NOT NULL,
  label       TEXT,
  url         TEXT,
  path        TEXT,
  mime_type   TEXT
);
CREATE INDEX idx_card_artifact ON workboard_card_artifacts(card_id, created_at);

-- 9) 诊断（stranded_ready/running_without_heartbeat/blocked_too_long/repeated_failures/missing_proof/orphaned_session/archived_but_active）
CREATE TABLE workboard_card_diagnostics (
  id            TEXT PRIMARY KEY,
  card_id       TEXT NOT NULL REFERENCES workboard_card(id) ON DELETE CASCADE,
  kind          TEXT NOT NULL,
  severity      TEXT NOT NULL,
  title         TEXT NOT NULL,
  detail        TEXT,
  first_seen_at INTEGER NOT NULL,
  last_seen_at  INTEGER NOT NULL,
  count         INTEGER NOT NULL DEFAULT 1,
  actions_json  TEXT
);
CREATE INDEX idx_card_diag ON workboard_card_diagnostics(card_id, kind);

-- 10) 附件元数据
CREATE TABLE workboard_card_attachments (
  id          TEXT PRIMARY KEY,
  card_id     TEXT NOT NULL REFERENCES workboard_card(id) ON DELETE CASCADE,
  created_at  INTEGER NOT NULL,
  file_name   TEXT NOT NULL,
  byte_size   INTEGER NOT NULL,
  mime_type   TEXT,
  note        TEXT
);
CREATE INDEX idx_card_attach ON workboard_card_attachments(card_id, created_at);

-- 11) 附件二进制
CREATE TABLE workboard_attachment_blobs (
  id      TEXT PRIMARY KEY,
  blob    BLOB
);

-- 12) Worker 日志
CREATE TABLE workboard_worker_logs (
  id          TEXT PRIMARY KEY,
  card_id     TEXT REFERENCES workboard_card(id) ON DELETE CASCADE,
  level       TEXT NOT NULL,
  message     TEXT NOT NULL,
  session_key TEXT,
  run_id      TEXT,
  created_at  INTEGER NOT NULL
);
CREATE INDEX idx_worker_log ON workboard_worker_logs(card_id, created_at);

-- 13) Worker 协议状态（idle/running/completed/blocked/violated）
CREATE TABLE workboard_worker_protocol (
  card_id     TEXT PRIMARY KEY REFERENCES workboard_card(id) ON DELETE CASCADE,
  state       TEXT NOT NULL,
  updated_at  INTEGER NOT NULL,
  detail      TEXT
);

-- 14) 通知订阅
CREATE TABLE workboard_notification_subscriptions (
  id              TEXT PRIMARY KEY,
  board_id        TEXT,
  card_id         TEXT,
  session_key     TEXT,
  run_id          TEXT,
  target          TEXT,
  event_kinds_json TEXT,
  last_event_at   INTEGER,
  last_event_id   TEXT,
  last_event_seq  INTEGER,
  delivered_json  TEXT,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);
CREATE INDEX idx_notif_sub_board ON workboard_notification_subscriptions(board_id);
CREATE INDEX idx_notif_sub_card  ON workboard_notification_subscriptions(card_id);

-- 15) 通知记录
CREATE TABLE workboard_card_notifications (
  id          TEXT PRIMARY KEY,
  card_id     TEXT NOT NULL REFERENCES workboard_card(id) ON DELETE CASCADE,
  kind        TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  sequence    INTEGER,
  message     TEXT,
  session_key TEXT,
  run_id      TEXT
);
CREATE INDEX idx_card_notif ON workboard_card_notifications(card_id, created_at);
