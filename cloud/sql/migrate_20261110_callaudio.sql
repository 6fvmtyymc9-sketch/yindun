-- 号码网：允许"录音复盘"作为上报来源
--
-- 背景：群体免疫号码网要把「银盾判定为诈骗」和「用户自己举报」分开计数，
-- 前者写进 yd_report 时 reporter='callaudio'。原来的 ENUM 只认 elder/child，
-- 不改这里，MySQL 会直接把这个值截断成空串，数据静默丢失（不报错、不提醒）。
--
-- 用法：mysql -uroot -p yindun < sql/migrate_20261110_callaudio.sql
-- 可重复执行：ENUM 已经是新值时会整体重写一遍，结果不变。

ALTER TABLE yd_report
  MODIFY COLUMN reporter ENUM('elder','child','callaudio')
    NOT NULL DEFAULT 'elder'
    COMMENT '谁提交的：elder=老人自己，child=子女代传，callaudio=录音复盘判定';

ALTER TABLE yd_report
  MODIFY COLUMN source ENUM('text','image','call')
    NOT NULL DEFAULT 'text'
    COMMENT '来源：text=文字，image=截图，call=通话录音复盘';
