-- 升级现有开发库：给 resume_profile 增加 Spring AI 结构化字段。
--
-- 仅用于把 day01 的旧表（id/resume_id/raw_text/created_at/updated_at）升级到当前结构；
-- 新库直接执行 schema.sql 即可。全部 IF NOT EXISTS，可重复执行，不会删除或改动现有数据：
--   docker cp upgrade-resume-profile-structured.sql postgres:/upgrade.sql
--   docker exec postgres psql -U postgres -d postgres -f /upgrade.sql

ALTER TABLE resume_profile
    ADD COLUMN IF NOT EXISTS name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS phone VARCHAR(50),
    ADD COLUMN IF NOT EXISTS email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS location VARCHAR(255),
    ADD COLUMN IF NOT EXISTS summary TEXT,
    ADD COLUMN IF NOT EXISTS education JSONB,
    ADD COLUMN IF NOT EXISTS work_experience JSONB,
    ADD COLUMN IF NOT EXISTS project_experience JSONB,
    ADD COLUMN IF NOT EXISTS skills JSONB;
