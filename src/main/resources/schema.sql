-- CodeBuddy 账户信息表
--
-- 凭证说明（access_token 与 api_key 是等效的认证方式，互补使用）：
--   - access_token: 从 CodeBuddy 本地配置中读取的 JWT Access Token
--   - api_key:      用户在系统中手动输入的 API Key（与 JWT 等价）
-- 通常有 JWT 时不会同时存在 API Key
--
-- id:           自增编号
-- uid:          账户 UID（唯一约束）
-- account_json: 完整账户 JSON 内容
-- extra:        预留扩展字段，后续存储控制账号状态的 JSON 参数

CREATE TABLE IF NOT EXISTS workbuddy_account (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    uid           TEXT    NOT NULL UNIQUE,
    account_json  TEXT    NOT NULL,
    access_token  TEXT    NULL,
    api_key       TEXT    NULL,
    extra         TEXT    NULL,
    created_at    INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000),
    updated_at    INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000)
);

CREATE INDEX IF NOT EXISTS idx_workbuddy_account_uid ON workbuddy_account(uid);
