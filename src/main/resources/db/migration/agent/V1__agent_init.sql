-- 龙虾 agent 库 V1：会话/消息/Part/输入收件箱/事件溯源
-- 依据《07-龙虾-表设计.md》第二章

CREATE TABLE session (
  id            TEXT PRIMARY KEY,
  session_key   TEXT NOT NULL,
  parent_id     TEXT REFERENCES session(id),
  session_scope TEXT NOT NULL DEFAULT 'conversation',
  kind          TEXT NOT NULL DEFAULT 'other',
  title         TEXT,
  icon          TEXT,
  category      TEXT,
  pinned        INTEGER NOT NULL DEFAULT 0,
  directory     TEXT NOT NULL,
  worktree      TEXT,
  agent_id      TEXT,
  model_provider TEXT,
  model_id      TEXT,
  permission_rules TEXT,
  approved_rules TEXT,
  queue_mode    TEXT NOT NULL DEFAULT 'steer',
  revert_state  TEXT,
  compaction_baseline TEXT,
  created_actor TEXT,
  owner         TEXT,
  unread        INTEGER NOT NULL DEFAULT 0,
  state_version INTEGER NOT NULL DEFAULT 0,
  archived_at   INTEGER,
  tokens_input  INTEGER NOT NULL DEFAULT 0,
  tokens_output INTEGER NOT NULL DEFAULT 0,
  cost          REAL NOT NULL DEFAULT 0,
  share_url     TEXT,
  spawned_by    TEXT,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);
CREATE UNIQUE INDEX idx_session_key ON session(session_key) WHERE archived_at IS NULL;
CREATE INDEX idx_session_parent ON session(parent_id);
CREATE INDEX idx_session_category ON session(category, updated_at);

CREATE TABLE session_participant (
  session_id    TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
  actor_type    TEXT NOT NULL,
  actor_id      TEXT NOT NULL,
  last_at       INTEGER NOT NULL,
  PRIMARY KEY (session_id, actor_type, actor_id)
);

CREATE TABLE message (
  id            TEXT PRIMARY KEY,
  session_id    TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
  role          TEXT NOT NULL,
  data          TEXT NOT NULL,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);
CREATE INDEX idx_msg_session ON message(session_id, created_at, id);

CREATE TABLE part (
  id            TEXT PRIMARY KEY,
  session_id    TEXT NOT NULL,
  message_id    TEXT NOT NULL REFERENCES message(id) ON DELETE CASCADE,
  type          TEXT NOT NULL,
  data          TEXT NOT NULL,
  created_at    INTEGER NOT NULL
);
CREATE INDEX idx_part_msg ON part(message_id, id);
CREATE INDEX idx_part_session ON part(session_id, id);

CREATE TABLE session_input (
  id            TEXT PRIMARY KEY,
  session_id    TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
  prompt        TEXT NOT NULL,
  delivery      TEXT NOT NULL,
  admitted_seq  INTEGER NOT NULL,
  promoted_seq  INTEGER,
  created_at    INTEGER NOT NULL,
  UNIQUE (session_id, admitted_seq)
);

CREATE TABLE session_active_writer (
  session_key   TEXT PRIMARY KEY,
  run_id        TEXT NOT NULL,
  generation    TEXT NOT NULL,
  claimed_at    INTEGER NOT NULL
);

CREATE TABLE todo (
  session_id    TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
  position      INTEGER NOT NULL,
  content       TEXT NOT NULL,
  status        TEXT NOT NULL,
  priority      TEXT NOT NULL DEFAULT 'medium',
  updated_at    INTEGER NOT NULL,
  PRIMARY KEY (session_id, position)
);

CREATE TABLE branch (
  id            TEXT PRIMARY KEY,
  session_id    TEXT NOT NULL REFERENCES session(id) ON DELETE CASCADE,
  leaf_entry_id TEXT,
  name          TEXT,
  is_active     INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL
);

CREATE TABLE event_sequence (
  aggregate_id  TEXT PRIMARY KEY,
  seq           INTEGER NOT NULL,
  owner_id      TEXT
);

CREATE TABLE event (
  id            TEXT PRIMARY KEY,
  aggregate_id  TEXT NOT NULL REFERENCES event_sequence(aggregate_id) ON DELETE CASCADE,
  seq           INTEGER NOT NULL,
  type          TEXT NOT NULL,
  data          TEXT NOT NULL,
  created_at    INTEGER NOT NULL,
  UNIQUE (aggregate_id, seq)
);
CREATE INDEX idx_event_type ON event(aggregate_id, type, seq);

CREATE TABLE session_state_signal (
  id            TEXT PRIMARY KEY,
  session_key   TEXT NOT NULL,
  state_version INTEGER NOT NULL,
  kind          TEXT NOT NULL,
  payload       TEXT,
  created_at    INTEGER NOT NULL
);
CREATE INDEX idx_signal ON session_state_signal(session_key, state_version);
