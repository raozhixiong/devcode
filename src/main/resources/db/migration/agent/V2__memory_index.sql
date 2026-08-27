-- 龙虾 agent 库 V2：五层记忆索引（memory_source/memory_chunk + provenance 闸门）
-- 依据《07-龙虾-表设计.md》§2.3

CREATE TABLE memory_source (
  id            TEXT PRIMARY KEY,
  path          TEXT NOT NULL,
  tier          TEXT NOT NULL,
  updated_at    INTEGER NOT NULL,
  UNIQUE (path)
);

CREATE TABLE memory_chunk (
  id            TEXT PRIMARY KEY,
  source_id     TEXT NOT NULL REFERENCES memory_source(id) ON DELETE CASCADE,
  chunk_index   INTEGER NOT NULL,
  content       TEXT NOT NULL,
  origin_class  TEXT NOT NULL,
  created_at    INTEGER NOT NULL
);
CREATE INDEX idx_chunk_source ON memory_chunk(source_id);
CREATE INDEX idx_chunk_content ON memory_chunk(content);
