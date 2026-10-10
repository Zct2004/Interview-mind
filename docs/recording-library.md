# 录音资料库与去重

## 用户体验

- `GET /api/recording-reviews` 返回音频资料库，而不只是已经分析过的报告；每个音频项包含文件元数据和可空的 `review`。
- 列表包含已上传但尚未分析的录音、已生成报告的录音，以及历史上只存在 `recording_review` 的旧报告。
- 刷新页面后，前端重新从 PostgreSQL 加载音频列表；用户可直接查看报告或对已有 objectKey 生成报告，不需要重新选择或上传本地文件。
- 上传时计算 SHA-256。相同内容返回已有 objectKey，不再创建新的 MinIO 对象；对旧的同名同大小文件，会按需下载候选对象核验哈希，不会扫描整个 MinIO bucket。
- 已有 `COMPLETED` 报告时，再次请求分析会直接返回已有报告，不重复调用 ASR/LLM。要重新分析的能力暂不开放，避免误触造成重复费用。

## 已有数据库手动升级

本项目不使用 Flyway。请在后端启动前，从项目根目录执行：

```powershell
docker cp .\src\main\resources\sql\audio-library-dedup.sql postgres:/tmp/audio-library-dedup.sql
docker exec postgres psql -U postgres -d postgres -f /tmp/audio-library-dedup.sql
```

该脚本只为 `file_metadata` 增加可空 `content_sha256` 字段及唯一索引，不删除、重命名或覆盖任何 MinIO 对象，也不删除数据库里的录音或报告。

旧数据的哈希不会在升级时批量计算。用户之后上传与旧文件同名、同大小的音频时，系统才按需校验对应 MinIO 对象；已经带哈希的新文件可直接按内容识别重复。

## 报告与 RAG

报告仍由 `recording_review` 与 `recording_review_question` 持久化，ASR 原文不写入数据库。录音分析完成后可以查看报告；需要加入个性化模拟面试知识库时，仍需在报告详情中选择简历并显式点击“加入个人 RAG 知识库”。