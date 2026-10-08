-- 模拟面试 4 张表的幂等升级脚本（无 Flyway/Liquibase，手动执行）：
--   docker cp upgrade-interview-tables.sql postgres:/tmp/upgrade.sql
--   docker exec postgres psql -U postgres -d postgres -f /tmp/upgrade.sql
--
-- 只新增表，不修改已有表结构，不删除已有数据。

-- 模拟面试会话
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

-- 面试题快照（(session_id, question_no) 唯一）
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

-- 用户回答（(question_id) 唯一；AI 整体评价字段先为 NULL）
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

-- 整场面试 AI 报告（(session_id) 唯一）
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
