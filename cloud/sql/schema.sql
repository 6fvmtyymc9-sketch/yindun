-- ============================================================================
--  银盾 · RDS for MySQL 建表脚本
--  在华为云 RDS 控制台 → 数据库管理 → SQL 窗口 里整段执行即可
-- ============================================================================

CREATE DATABASE IF NOT EXISTS yindun
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE yindun;

-- ---------------------------------------------------------------------------
--  1. 事件流水：每一条手机状态都记下来，形成完整证据链
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS yd_event (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  elder_id    VARCHAR(32)     NOT NULL COMMENT '老人/设备标识',
  type        VARCHAR(16)     NOT NULL COMMENT 'sms/call/app/install/a11y/notify',
  code        VARCHAR(32)              DEFAULT NULL COMMENT '具体判据代码，如 bank_app',
  level       VARCHAR(12)     NOT NULL DEFAULT 'none' COMMENT 'none/watch/warn/high/block',
  score       INT             NOT NULL DEFAULT 0 COMMENT '风险分',
  why         VARCHAR(512)             DEFAULT NULL COMMENT '命中原因，给评委复现用',
  payload     JSON                     DEFAULT NULL COMMENT '原始事件',
  created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_elder_time (elder_id, created_at),
  KEY idx_level (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手机事件流水';

-- ---------------------------------------------------------------------------
--  2. 预警记录：推给子女的每一条卡片
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS yd_alert (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  elder_id    VARCHAR(32)     NOT NULL,
  level       VARCHAR(12)     NOT NULL,
  title       VARCHAR(128)             DEFAULT NULL,
  body        VARCHAR(512)             DEFAULT NULL,
  why         VARCHAR(512)             DEFAULT NULL,
  summary     TEXT                     DEFAULT NULL COMMENT 'AI 生成的人话摘要（异步补写）',
  channel     VARCHAR(24)              DEFAULT 'push' COMMENT 'push / push+call',
  ack_at      DATETIME                 DEFAULT NULL COMMENT '子女已读时间',
  created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_elder_time (elder_id, created_at),
  KEY idx_level (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='子女端预警记录';

-- ---------------------------------------------------------------------------
--  3. 规则版本：A 线每次改词库都存一版，可回滚、可对比
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS yd_rules (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  version     VARCHAR(32)     NOT NULL,
  rules       JSON            NOT NULL COMMENT '完整的 rules.json',
  note        VARCHAR(255)             DEFAULT NULL,
  created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_version (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则版本';

-- ---------------------------------------------------------------------------
--  4. 熟人标记：老人点"是认识的"的记录
--     注意：这张表只是日志，不参与任何判级。
--     点"认识"不会关闭防护，这是产品铁律。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS yd_known (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  elder_id    VARCHAR(32)     NOT NULL,
  number      VARCHAR(24)              DEFAULT NULL,
  marked_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expire_at   DATETIME                 DEFAULT NULL COMMENT '15 分钟后过期',
  PRIMARY KEY (id),
  KEY idx_elder (elder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='熟人标记日志（不影响判级）';

-- ---------------------------------------------------------------------------
--  5. 演示账号：两台手机的身份
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS yd_user (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id     VARCHAR(32)     NOT NULL COMMENT 'elder01 / child01',
  role        VARCHAR(12)     NOT NULL COMMENT 'elder / child',
  name        VARCHAR(64)              DEFAULT NULL,
  phone       VARCHAR(24)              DEFAULT NULL,
  bind_to     VARCHAR(32)              DEFAULT NULL COMMENT '子女绑定的老人',
  created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户身份';

-- 诈骗信息上报（一键上传）：老人/子女主动上传的可疑短信或截图
-- 注意：不存原图，只存字节数与摘要 —— 截图里可能有姓名、卡号
CREATE TABLE IF NOT EXISTS yd_report (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  elder_id    VARCHAR(64)     NOT NULL DEFAULT 'demo',
  reporter    ENUM('elder','child','callaudio') NOT NULL DEFAULT 'elder' COMMENT '谁提交的：elder=老人自己，child=子女代传，callaudio=录音复盘判定',
  number      VARCHAR(32)     NOT NULL DEFAULT ''            COMMENT '可疑号码，用于信誉库',
  source      ENUM('text','image','call') NOT NULL DEFAULT 'text',
  excerpt     VARCHAR(200)    NOT NULL DEFAULT ''            COMMENT '文字摘录，最多200字',
  image_bytes INT UNSIGNED    NOT NULL DEFAULT 0             COMMENT '原图字节数（不存图）',
  is_fraud    TINYINT(1)      NOT NULL DEFAULT 0,
  stage       TINYINT         NOT NULL DEFAULT 0             COMMENT '0-4 四阶段',
  type        VARCHAR(32)     NOT NULL DEFAULT ''            COMMENT '诈骗类型',
  reason      VARCHAR(64)     NOT NULL DEFAULT '',
  advice      VARCHAR(64)     NOT NULL DEFAULT '',
  signals     VARCHAR(512)    NOT NULL DEFAULT '[]'          COMMENT '关键话术片段 JSON',
  local_score INT             NOT NULL DEFAULT 0             COMMENT '本地规则分数，用于对照',
  cost        DECIMAL(10,6)   NOT NULL DEFAULT 0             COMMENT '本次模型花费（元）',
  created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_number (number),
  KEY idx_elder (elder_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='诈骗信息上报';

-- 演练记录（定期演练，功能④反诈疫苗）
-- 只存一局一条汇总，不存对话明细：明细属于隐私，且复盘用不到
CREATE TABLE IF NOT EXISTS yd_drill (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  elder_id     VARCHAR(64)     NOT NULL DEFAULT 'demo',
  scenario     VARCHAR(32)     NOT NULL DEFAULT ''       COMMENT '场景 key',
  score        TINYINT         NOT NULL DEFAULT 0        COMMENT '0-100',
  rounds       TINYINT         NOT NULL DEFAULT 4,
  passed       TINYINT(1)      NOT NULL DEFAULT 0        COMMENT '>=75 及格',
  wrong_stages VARCHAR(128)    NOT NULL DEFAULT '[]'     COMMENT '失守的阶段 JSON，如 [3,4]',
  cost         DECIMAL(10,6)   NOT NULL DEFAULT 0        COMMENT '本局模型花费（元）',
  created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_elder (elder_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='反诈演练记录';

-- 预置演示数据
INSERT IGNORE INTO yd_user (user_id, role, name, bind_to) VALUES
  ('demo',     'elder', '王秀兰', NULL),
  ('demo_son', 'child', '李伟',   'demo');

-- 预置规则版本（第一次部署时把本地 rules.json 内容灌进来）
-- 实际由服务启动时自动写入，这里留空即可。
