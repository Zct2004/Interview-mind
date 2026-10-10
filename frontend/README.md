# InterviewMind AI 前端

基于 Vue 3、TypeScript 和 Vite 的模拟面试界面，调用现有 Spring Boot API。

## 环境要求

- Node.js 20.19+ 或 22.12+
- 后端默认运行在 `http://localhost:8080`

## 本地启动

在项目根目录执行：

```powershell
cd frontend
npm install
npm run dev
```

打开 Vite 输出的地址，默认是 `http://localhost:5173`。开发服务器会将 `/api` 请求代理到 Spring Boot 的 `http://localhost:8080`。

如果后端地址不同，可以复制 `.env.example` 为 `.env.local` 并修改 `VITE_API_TARGET`。

## 构建与类型检查

```powershell
npm run type-check
npm run build
```

## 当前模拟面试流程

1. 上传 PDF 简历，文件由后端保存到 MinIO。
2. 调用后端简历解析接口，生成简历画像。
3. 开始面试，由后端基于简历生成题目并创建面试会话。
4. 逐题提交回答；页面刷新后会尝试恢复未完成会话。
5. 全部回答完成后，调用后端结束接口，展示真实返回的 AI 评价报告。

前端不会伪造题目或评分。后端未连接、简历解析失败、模型调用失败等情况会显示对应错误信息。
