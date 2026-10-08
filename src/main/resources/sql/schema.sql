-- InterviewMind AI 数据库结构（手动维护）
--
-- 本项目不使用任何数据库迁移工具，表结构变更直接维护在本文件中，
-- 需要时手动在 PostgreSQL 执行：
--   docker cp schema.sql postgres:/schema.sql
--   docker exec postgres psql -U postgres -d postgres -f /schema.sql
--
-- 面试相关表（interview_session/interview_question/interview_answer/interview_report）
-- 已在本文件末尾创建。
-- 当前阶段还没有用户系统，暂不创建 users 表。
-- 后续计划创建的表：
--   users, knowledge_documents, knowledge_chunks,
--   ability_profile, rag_document, embedding 等（后续阶段设计）

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

-- 模拟面试会话。current_question 保存在数据库中，刷新页面后可恢复面试进度。
-- status: CREATED / IN_PROGRESS / COMPLETED / FAILED（本阶段不引入复杂状态机）
CREATE TABLE IF NOT EXISTS interview_session
(
    id               BIGSERIAL PRIMARY KEY,
    resume_id        BIGINT      NOT NULL,
    status           VARCHAR(30) NOT NULL,
    total_questions  INT         NOT NULL,
    current_question INT         NOT NULL,
    started_at       TIMESTAMP   NOT NULL,
    finished_at      TIMESTAMP,
    created_at       TIMESTAMP   NOT NULL,
    updated_at       TIMESTAMP   NOT NULL,
    CONSTRAINT fk_interview_session_resume
        FOREIGN KEY (resume_id) REFERENCES resume (id) ON DELETE CASCADE
);

-- 本次面试实际使用的题目快照。面试开始时由 AI 生成 20 道题后一次性写入，
-- 会话期间不再重复调用 LLM，保证一场面试题目固定。
CREATE TABLE IF NOT EXISTS interview_question
(
    id               BIGSERIAL PRIMARY KEY,
    session_id       BIGINT       NOT NULL,
    question_no      INT          NOT NULL,
    question         TEXT         NOT NULL,
    type             VARCHAR(30)  NOT NULL,
    difficulty       VARCHAR(30)  NOT NULL,
    knowledge_point  VARCHAR(100),
    reason           VARCHAR(255),
    reference_answer TEXT,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    CONSTRAINT fk_interview_question_session
        FOREIGN KEY (session_id) REFERENCES interview_session (id) ON DELETE CASCADE,
    CONSTRAINT uk_interview_question_session_no UNIQUE (session_id, question_no)
);

-- 用户对某道题的回答。刚提交时 score/analysis/weak_points/suggestions 为 NULL，
-- 整场面试结束后由 AI 整体评价一次性补充（不逐题调用 LLM）。
CREATE TABLE IF NOT EXISTS interview_answer
(
    id          BIGSERIAL PRIMARY KEY,
    question_id BIGINT       NOT NULL UNIQUE,
    answer_text TEXT         NOT NULL,
    score       INT,
    analysis    TEXT,
    weak_points JSONB,
    suggestions JSONB,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT fk_interview_answer_question
        FOREIGN KEY (question_id) REFERENCES interview_question (id) ON DELETE CASCADE
);

-- 整场面试的 AI 总结报告（一个会话一份）。strengths/weak_points/suggestions 为 JSONB 字符串数组。
-- 后续 RAG 阶段将基于本表构造 Interview Memory 文档。
CREATE TABLE IF NOT EXISTS interview_report
(
    id          BIGSERIAL PRIMARY KEY,
    session_id  BIGINT      NOT NULL UNIQUE,
    total_score INT,
    summary     TEXT,
    strengths   JSONB,
    weak_points JSONB,
    suggestions JSONB,
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL,
    CONSTRAINT fk_interview_report_session
        FOREIGN KEY (session_id) REFERENCES interview_session (id) ON DELETE CASCADE
);
