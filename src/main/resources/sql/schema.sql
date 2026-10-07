-- InterviewMind AI 数据库结构（手动维护）
--
-- 本项目不使用任何数据库迁移工具，表结构变更直接维护在本文件中，
-- 需要时手动在 PostgreSQL 执行：
--   docker cp schema.sql postgres:/schema.sql
--   docker exec postgres psql -U postgres -d postgres -f /schema.sql
--
-- 当前阶段还没有用户系统，暂不创建 users 表。
-- 后续计划创建的表：
--   users, interviews, interview_questions, interview_answers,
--   knowledge_documents, knowledge_chunks, interview_reports

-- 文件元数据（简历、录音等所有上传文件共用）
CREATE TABLE IF NOT EXISTS file_metadata
(
    id            BIGSERIAL PRIMARY KEY,
    original_name VARCHAR(255) NOT NULL,
    object_key    VARCHAR(500) NOT NULL,
    bucket_name   VARCHAR(100) NOT NULL,
    content_type  VARCHAR(100),
    file_size     BIGINT,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL
);

-- 简历
CREATE TABLE IF NOT EXISTS resume
(
    id         BIGSERIAL PRIMARY KEY,
    file_id    BIGINT       NOT NULL,
    status     VARCHAR(30)  NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    CONSTRAINT fk_resume_file_metadata FOREIGN KEY (file_id) REFERENCES file_metadata (id)
);

-- 简历解析结果（一个 resume 最多一个 profile）。
-- raw_text 保存 PDF 提取的原始文本；结构化字段由 Spring AI 从 rawText 抽取，
-- JSONB 列（education/work_experience/project_experience/skills）存 JSON 数组。
CREATE TABLE IF NOT EXISTS resume_profile
(
    id                 BIGSERIAL PRIMARY KEY,
    resume_id          BIGINT       NOT NULL UNIQUE,
    name               VARCHAR(100),
    phone              VARCHAR(50),
    email              VARCHAR(255),
    location           VARCHAR(255),
    summary            TEXT,
    education          JSONB,
    work_experience    JSONB,
    project_experience JSONB,
    skills             JSONB,
    raw_text           TEXT,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL,
    CONSTRAINT fk_resume_profile_resume
        FOREIGN KEY (resume_id) REFERENCES resume (id) ON DELETE CASCADE
);
