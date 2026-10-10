-- 录音资料库升级：为文件元数据增加内容哈希，实现相同音频去重。
-- 安全：只增加可空字段与唯一索引，不删除或覆盖 MinIO 对象。
ALTER TABLE file_metadata
    ADD COLUMN IF NOT EXISTS content_sha256 VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uk_file_metadata_content_sha256
    ON file_metadata (content_sha256)
    WHERE content_sha256 IS NOT NULL;
