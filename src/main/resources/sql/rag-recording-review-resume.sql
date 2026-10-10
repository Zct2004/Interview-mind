-- InterviewMind AI RAG upgrade: associate each recording review with its resume.
-- Run manually against the existing PostgreSQL database; no Flyway is used.
ALTER TABLE recording_review
    ADD COLUMN IF NOT EXISTS resume_id BIGINT REFERENCES resume(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_recording_review_resume_id
    ON recording_review (resume_id, vector_status, updated_at);
