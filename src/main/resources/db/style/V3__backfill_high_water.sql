-- 首次回填记录固定高水位，尾部不足触发条数也继续处理；之后的新消息恢复正常聚合。
ALTER TABLE learning_checkpoint ADD COLUMN backfill_through bigint NOT NULL DEFAULT 0;
