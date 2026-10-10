# InterviewMind AI：个人 RAG 知识库流程

## 数据流

1. **录音复盘**：录音保留在 MinIO；ASR 文本只在请求内存中传递；LLM 结构化复盘保存到 `recording_review` 与 `recording_review_question`。
2. **显式建立索引**：用户在录音复盘页面选择一份简历并点击“加入个人 RAG 知识库”。后端把复盘总结、问题、回答、缺失点、参考答案和改进答案映射为 PGVector 文档。该动作会调用 Embedding；录音解析本身不会自动调用 Embedding。
3. **模拟面试经验积累**：模拟面试完成后，后端把逐题薄弱点、优秀回答模式和整场总结写入同一个 PGVector 知识库，并使用 `resumeId` 作为归属过滤条件。
4. **个性化出题**：开始模拟面试时，后端用当前简历中的技能与项目摘要进行语义检索，并按 `resumeId` 过滤。知识点权重由后端根据历史评分和薄弱点计算，命中的历史经验会注入出题 Prompt。
5. **降级行为**：没有历史记忆时按简历正常生成；RAG 检索失败时记录错误并回退普通出题。题目仍为 20 道，遵守既有难度与输出约束。

## 已有数据库升级

本项目不使用 Flyway。对已经创建过 `recording_review` 的数据库，需要手动执行一次：

```powershell
docker cp .\src\main\resources\sql\rag-recording-review-resume.sql postgres:/tmp/rag-recording-review-resume.sql
docker exec postgres psql -U postgres -d postgres -f /tmp/rag-recording-review-resume.sql
```

该脚本只新增 `recording_review.resume_id` 可空关联列和索引，不删除录音、复盘记录或现有业务数据。新建数据库时，`schema.sql` 已包含该列。

## 使用步骤

1. 启动后端并确认已设置 `OPENAI_API_KEY` 环境变量。
2. 在前端“录音复盘”页面完成一次录音分析。
3. 在“模拟面试”页面选择对应的已解析简历，再返回录音复盘页面。
4. 点击“加入个人 RAG 知识库”。首次索引接口为 `POST /api/recording-reviews/{reviewId}/index?resumeId={resumeId}`。成功后 `vectorStatus` 为 `INDEXED`。
5. 开始模拟面试。出题流程会自动检索这份简历关联的历史模拟面试与录音复盘记忆。

如果索引失败，复盘结构化数据仍保留，页面会提供重试入口。首次关联后不能把同一条录音复盘移动到另一份简历，避免个人知识库串数据。

## 验证边界

编译、无外部模型调用的向量索引单元测试和前端构建可在本地执行。端到端 Embedding / PGVector 检索与模型出题需要用户显式触发；开发验证不会自动发起付费模型请求。