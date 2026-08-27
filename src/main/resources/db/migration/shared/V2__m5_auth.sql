-- M5 认证：user 表增加 password_hash 列
ALTER TABLE user ADD COLUMN password_hash TEXT;
