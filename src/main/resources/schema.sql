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

-- 签到日志表
--
-- 用途：记录每个账号的签到行为，作为"今日是否签到"的权威源
-- 设计原则：
--   - account_id 非唯一（一个账号可以有多条签到记录：每日/活动等）
--   - checkin_type 预留未来扩展（daily / weekly / event_xxx）
--   - ON DELETE CASCADE：账号被删除时，签到日志同步清理
--   - "当日"判定由应用层按 account_id + checkin_time 范围查询，避开 SQLite strftime 的时区陷阱
--
-- 字段说明：
--   id:            自增编号
--   account_id:    签到账号的 id（对应 workbuddy_account.id）
--   checkin_type:  签到类型，默认 'daily'
--   checkin_time:  签到时间（毫秒时间戳，与 workbuddy_account.created_at 一致）
--   extra:         预留 JSON 扩展（如上游返回的 credit / streak_days 等）

CREATE TABLE IF NOT EXISTS checkin_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id    INTEGER NOT NULL,
    checkin_type  TEXT    NOT NULL DEFAULT 'daily',
    checkin_time  INTEGER NOT NULL,
    extra         TEXT    NULL,
    FOREIGN KEY (account_id) REFERENCES workbuddy_account(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_checkin_log_account_time ON checkin_log(account_id, checkin_time);
CREATE INDEX IF NOT EXISTS idx_checkin_log_time ON checkin_log(checkin_time);
