-- 提取规则升级使用独立游标，旧片段与任务保留作证据，不混入新版本检索。
ALTER TABLE learning_checkpoint ADD COLUMN extractor_version text NOT NULL DEFAULT 'v1';
ALTER TABLE learning_checkpoint DROP CONSTRAINT learning_checkpoint_pkey;
ALTER TABLE learning_checkpoint ADD PRIMARY KEY(conversation_id,extractor_version);
