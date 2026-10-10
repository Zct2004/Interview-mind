# 录音资料库与去重

## 用户体验

- `GET /api/recording-reviews` 返回音频资料库，而不只是已经分析过的报告；每个音频项包含文件元数据和可空的 `review`。
- 列表包含已上传但尚未分析的录音、已生成报告的录音，以及历史上只存在 `recording_review` 的旧报告。
- 刷新页面后，前端重新从 PostgreSQL 加载音频列表；用户可直接查看报告或对已有 objectKey 生成报告，不需要重新选择或上传本地文件。
- 上传时由后端计算文件内容 SHA-256。相同内容返回 HTTP 409，并提示已有录音，不会写入 MinIO，也不会触发 ASR、LLM 或 Embedding；进程内哈希锁与数据库唯一索引共同防止并发上传竞态。
- 历史文件尚无哈希时，只核验同大小的历史音频对象（不扫描整个 bucket），可识别更改过文件名的重复音频。
- 已有 `COMPLETED` 报告时，再次请求分析会直接返回已有报告，不重复调用 ASR/LLM。并发重复分析请求会被拒绝，避免重复消耗额度。
- 录音资料库提供删除按钮；确认后删除录音对象、复盘报告/题目以及仅属于该报告的 RAG 文档。分析或索引期间拒绝删除。

## 已有数据库手动升级

本项目不使用 Flyway。请在后端启动前，从项目根目录执行：

```powershell
docker cp .\src\main\resources\sql\audio-library-dedup.sql postgres:/tmp/audio-library-dedup.sql
docker exec postgres psql -U postgres -d postgres -f /tmp/audio-library-dedup.sql
```

该脚本只为 `file_metadata` 增加可空 `content_sha256` 字段及唯一索引，不删除、重命名或覆盖任何 MinIO 对象，也不删除数据库里的录音或报告。

旧数据的哈希不会在升级时批量计算。用户上传新文件时，系统只下载并核验同大小的历史音频候选对象；已经带哈希的新文件可直接按内容识别重复。该迁移需手动执行，应用不会自动更改数据库，也不会删除现有文件或记录。

## 报告与 RAG

报告仍由 `recording_review` 与 `recording_review_question` 持久化，ASR 原文不写入数据库。录音分析完成后可以查看报告；需要加入个性化模拟面试知识库时，仍需在报告详情中选择简历并显式点击“加入个人 RAG 知识库”。

删除接口：`DELETE /api/recording-reviews?objectKey={objectKey}`。删除前会检查是否正在分析/索引，并拒绝删除仍被简历引用的文件元数据。若外部存储或 RAG 清理失败，接口返回错误供排查，不会静默报告成功。