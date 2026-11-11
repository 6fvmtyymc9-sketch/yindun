-- 事件回放：把一次通话里发生的多件事归到一组
--
-- 背景：子女端要看的不是"六条互不相干的预警"，而是"这一通电话里到底发生了什么"：
--   陌生号响铃 → 接通 → 满 5 分钟复问 → 通话中收到验证码
--   → 挂断后打开手机银行 → 红屏拦住 → 自动打给女儿
--
-- 归组不能靠时间戳猜（中间可能插进来一条无关短信），所以老人端在通话开始时
-- 生成一个 call_id，这一场里的每条上报都带上它。kind 是事件类型，
-- 让子女端决定图标和顺序，不受文案改动影响。
--
-- 用法：mysql -uroot -p yindun < sql/migrate_20261111_event.sql
-- 可重复执行：靠 information_schema 判断列是否已存在，已存在就跳过。

SET @s = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'yd_alert' AND COLUMN_NAME = 'call_id') = 0,
  'ALTER TABLE yd_alert ADD COLUMN call_id VARCHAR(40) DEFAULT NULL COMMENT ''事件组 id：同一通电话的所有上报共用一个''',
  'SELECT ''call_id 已存在，跳过'' AS msg'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @s = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'yd_alert' AND COLUMN_NAME = 'kind') = 0,
  'ALTER TABLE yd_alert ADD COLUMN kind VARCHAR(24) DEFAULT NULL COMMENT ''事件类型：ring/offhook/ask5/code/bank/judge/call_child/review/sms/install''',
  'SELECT ''kind 已存在，跳过'' AS msg'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 按组查用 (call_id, created_at)：一次事件的顺序按"真实发生时间"排，
-- 因为断网补发的事件到达顺序不等于发生顺序
SET @s = (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'yd_alert' AND INDEX_NAME = 'idx_call') = 0,
  'ALTER TABLE yd_alert ADD KEY idx_call (call_id, created_at)',
  'SELECT ''idx_call 已存在，跳过'' AS msg'));
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
