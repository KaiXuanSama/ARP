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

-- 应用设置表（key-value 结构，value 是 JSON 字符串）
--
-- 用途:全局配置项的统一存储位置,例如 "chat.consumption" 保存对话模式的消耗方式
-- 设计原则:
--   - key 唯一,采用 "类别.项" 命名(如 "chat.consumption" / "ui.theme" 未来)
--   - value 用 JSON 序列化,前端直接 JSON.parse 即可
--   - 不存任何"未知字段",加新设置项不需要改 schema,只需要 PUT 一次就行
--   - 时间戳用毫秒(跟 workbuddy_account / checkin_log 保持一致)
--
-- 字段说明:
--   id:         自增编号
--   key:        设置项唯一标识(由业务方定义,如 "chat.consumption")
--   value:      JSON 字符串,前端负责解析;后端只做字符串透传
--   created_at: 创建时间(毫秒)
--   updated_at: 最后更新时间(毫秒)

CREATE TABLE IF NOT EXISTS app_settings (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    key        TEXT    NOT NULL UNIQUE,
    value      TEXT    NOT NULL,
    created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000),
    updated_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000)
);

CREATE INDEX IF NOT EXISTS idx_app_settings_key ON app_settings(key);
