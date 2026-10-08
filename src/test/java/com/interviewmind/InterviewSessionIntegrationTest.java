package com.interviewmind;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.interviewmind.ai.InterviewEvaluationAiParser;
import com.interviewmind.ai.dto.InterviewEvaluationAiResult;
import com.interviewmind.ai.dto.QuestionEvaluationItem;
import com.interviewmind.entity.FileMetadata;
import com.interviewmind.entity.InterviewAnswer;
import com.interviewmind.entity.InterviewQuestion;
import com.interviewmind.entity.InterviewReport;
import com.interviewmind.entity.InterviewSession;
import com.interviewmind.entity.Resume;
import com.interviewmind.entity.ResumeProfile;
import com.interviewmind.mapper.FileMetadataMapper;
import com.interviewmind.mapper.InterviewAnswerMapper;
import com.interviewmind.mapper.InterviewQuestionMapper;
import com.interviewmind.mapper.InterviewReportMapper;
import com.interviewmind.mapper.InterviewSessionMapper;
import com.interviewmind.mapper.ResumeMapper;
import com.interviewmind.mapper.ResumeProfileMapper;
import com.interviewmind.memory.InterviewMemoryStore;
import com.interviewmind.memory.dto.InterviewMemory;
import com.interviewmind.memory.dto.InterviewMemory.MemoryType;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模拟面试流程集成测试（非 AI）。
 * <p>
 * 题目生成复用 {@link InterviewQuestionIntegrationTest.FakeQuestionAiConfig} 的确定性
 * InterviewQuestionAiParser，评价使用本类 {@link FakeEvaluationAiConfig} 的确定性
 * InterviewEvaluationAiParser；普通 mvn test 不消耗 token。
 * 真实 LLM 全链路由 {@link InterviewEvaluationAiIntegrationTest}（@Tag("ai")）覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
        InterviewQuestionIntegrationTest.FakeQuestionAiConfig.class,
        InterviewSessionIntegrationTest.FakeEvaluationAiConfig.class,
        // Memory 出口测试桩：普通测试不触发真实 Embedding / PgVector 写入
        FakeInterviewMemoryStoreConfig.class
})
class InterviewSessionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResumeMapper resumeMapper;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private ResumeProfileMapper resumeProfileMapper;

    @Autowired
    private InterviewSessionMapper sessionMapper;

    @Autowired
    private InterviewQuestionMapper questionMapper;

    @Autowired
    private InterviewAnswerMapper answerMapper;

    @Autowired
    private InterviewReportMapper reportMapper;

    @Autowired
    private InterviewMemoryStore memoryStore;

    @BeforeEach
    void resetData() {
        answerMapper.delete(new QueryWrapper<InterviewAnswer>());
        questionMapper.delete(new QueryWrapper<InterviewQuestion>());
        reportMapper.delete(new QueryWrapper<InterviewReport>());
        sessionMapper.delete(new QueryWrapper<InterviewSession>());
        resumeProfileMapper.delete(new QueryWrapper<ResumeProfile>());
        resumeMapper.delete(new QueryWrapper<Resume>());
        fileMetadataMapper.delete(new QueryWrapper<FileMetadata>());
    }

    // ---------- 1/5. 开始面试 ----------

    @Test
    void startCreatesSessionWith20QuestionSnapshot() throws Exception {
        long resumeId = seedParsedResume();

        MvcResult result = mockMvc.perform(post("/api/interviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":" + resumeId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.totalQuestions").value(20))
                .andExpect(jsonPath("$.data.firstQuestion.questionNo").value(1))
                .andExpect(jsonPath("$.data.firstQuestion.question").isNotEmpty())
                .andExpect(jsonPath("$.data.firstQuestion.type").isNotEmpty())
                .andExpect(jsonPath("$.data.firstQuestion.difficulty").isNotEmpty())
                .andExpect(jsonPath("$.data.firstQuestion.knowledgePoint").isNotEmpty())
                // 面试过程中不暴露参考答案与出题依据
                .andExpect(jsonPath("$.data.firstQuestion.referenceAnswer").doesNotExist())
                .andExpect(jsonPath("$.data.firstQuestion.reason").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        long sessionId = JsonPath.parse(body).read("$.data.sessionId", Long.class);

        // 5. 数据库中保存了 20 道题快照，题号 1..20 唯一
        List<InterviewQuestion> questions = questionMapper.selectList(
                new QueryWrapper<InterviewQuestion>().eq("session_id", sessionId));
        assertThat(questions).hasSize(20);
        assertThat(questions.stream().map(InterviewQuestion::getQuestionNo).sorted().toList())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                        11, 12, 13, 14, 15, 16, 17, 18, 19, 20);
        assertThat(questions).allMatch(q -> q.getReferenceAnswer() != null
                && !q.getReferenceAnswer().isBlank());

        InterviewSession session = sessionMapper.selectById(sessionId);
        assertThat(session.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(session.getCurrentQuestion()).isEqualTo(1);
        assertThat(session.getTotalQuestions()).isEqualTo(20);
        assertThat(session.getStartedAt()).isNotNull();
        assertThat(session.getFinishedAt()).isNull();
    }

    // ---------- 2/3/4. 开始面试的错误分支 ----------

    @Test
    void startReturns404WhenResumeNotFound() throws Exception {
        mockMvc.perform(post("/api/interviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void startReturns409WhenResumeNotParsed() throws Exception {
        long resumeId = seedResume(Resume.STATUS_UPLOADED, false);

        mockMvc.perform(post("/api/interviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":" + resumeId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void startReturns409WhenProfileMissing() throws Exception {
        long resumeId = seedResume(Resume.STATUS_PARSED, false);

        mockMvc.perform(post("/api/interviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":" + resumeId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    // ---------- RAG 读取端（无历史 / 有历史 均可开始面试） ----------

    @Test
    void startSucceedsWithoutHistoryMemories() throws Exception {
        store().clear(); // 第一轮：无任何历史 Memory
        long resumeId = seedParsedResume();

        mockMvc.perform(post("/api/interviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":" + resumeId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalQuestions").value(20));

        // RAG 检索被调用（单次），但无历史 → 走纯简历出题，不报错
        assertThat(store().searchCalls()).hasSize(1);
        FakeInterviewMemoryStoreConfig.SearchCall call = store().searchCalls().get(0);
        assertThat(call.resumeId()).isEqualTo(resumeId);
        assertThat(call.query()).isNotBlank();
    }

    @Test
    void startSucceedsWithHistoryMemories() throws Exception {
        store().clear();
        long resumeId = seedParsedResume();
        // 第二轮：向量库中已有该简历的历史记忆（Redis 薄弱 + JVM 良好）
        store().seedSearchable(List.of(
                memory(resumeId, "Redis分布式锁", MemoryType.WEAK_POINT, 4),
                memory(resumeId, "Redis分布式锁", MemoryType.ANSWER_PATTERN, 7),
                memory(resumeId, "JVM垃圾回收", MemoryType.ANSWER_PATTERN, 8),
                memory(resumeId + 100000, "MySQL索引优化", MemoryType.WEAK_POINT, 2)));

        MvcResult result = mockMvc.perform(post("/api/interviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":" + resumeId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalQuestions").value(20))
                .andReturn();

        // RAG 被调用：单次检索（禁止 N+1），且按 resumeId 过滤
        assertThat(store().searchCalls()).hasSize(1);
        FakeInterviewMemoryStoreConfig.SearchCall call = store().searchCalls().get(0);
        assertThat(call.resumeId()).isEqualTo(resumeId);
        assertThat(call.topK()).isEqualTo(30);

        // 落库的 20 道题快照（start 响应只暴露 firstQuestion）
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        long sessionId = JsonPath.parse(body).read("$.data.sessionId", Long.class);
        List<InterviewQuestion> questions = questionMapper.selectList(
                new QueryWrapper<InterviewQuestion>().eq("session_id", sessionId));
        assertThat(questions).hasSize(20);
        assertThat(questions).allMatch(q -> q.getQuestion() != null && !q.getQuestion().isBlank());
    }

    /** RAG 检索失败时开始面试仍成功（故障隔离，回退纯简历出题）。 */
    @Test
    void startSucceedsWhenRagSearchFails() throws Exception {
        store().clear();
        long resumeId = seedParsedResume();
        store().searchFailWith(new IllegalStateException("vector store unavailable"));

        mockMvc.perform(post("/api/interviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":" + resumeId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalQuestions").value(20));

        store().searchNoFail();
    }

    private FakeInterviewMemoryStoreConfig.CapturingMemoryStore store() {
        return (FakeInterviewMemoryStoreConfig.CapturingMemoryStore) memoryStore;
    }

    private static InterviewMemory memory(long resumeId, String knowledgePoint,
                                           MemoryType type, int score) {
        return new InterviewMemory(resumeId, 1L, 1L, type, knowledgePoint, score,
                "历史记忆：" + knowledgePoint + "，得分" + score + "。");
    }

    // ---------- 6. 获取当前题 ----------

    @Test
    void currentQuestionReturnsFirstQuestionWithoutAnswerFields() throws Exception {
        long sessionId = startSession();

        mockMvc.perform(get("/api/interviews/{id}/current-question", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.questionNo").value(1))
                .andExpect(jsonPath("$.data.totalQuestions").value(20))
                .andExpect(jsonPath("$.data.question").isNotEmpty())
                .andExpect(jsonPath("$.data.referenceAnswer").doesNotExist())
                .andExpect(jsonPath("$.data.reason").doesNotExist());
    }

    // ---------- 7/8. 提交答案与进度递增 ----------

    @Test
    void submitAnswerSavesAndAdvancesCurrentQuestion() throws Exception {
        long sessionId = startSession();

        MvcResult result = mockMvc.perform(post("/api/interviews/{id}/answers", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"我的第一题回答\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.interviewFinished").value(false))
                .andExpect(jsonPath("$.data.nextQuestion.questionNo").value(2))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> next = JsonPath.parse(body).read("$.data.nextQuestion");
        assertThat((String) next.get("question")).isNotBlank();
        assertThat(next).doesNotContainKey("referenceAnswer");

        InterviewSession session = sessionMapper.selectById(sessionId);
        assertThat(session.getCurrentQuestion()).isEqualTo(2);

        List<InterviewAnswer> answers = answerMapper.selectList(
                new QueryWrapper<InterviewAnswer>());
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).getAnswerText()).isEqualTo("我的第一题回答");
        // 刚提交时 AI 评价字段为空，等整场结束后统一补充
        assertThat(answers.get(0).getScore()).isNull();
        assertThat(answers.get(0).getAnalysis()).isNull();
    }

    // ---------- 10. 刷新恢复 ----------

    @Test
    void currentQuestionRecoversAfterRefresh() throws Exception {
        long sessionId = startSession();
        submitAnswer(sessionId, "第一题回答");

        // 刷新页面后从数据库恢复到第 2 题
        mockMvc.perform(get("/api/interviews/{id}/current-question", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.questionNo").value(2))
                .andExpect(jsonPath("$.data.referenceAnswer").doesNotExist());
    }

    // ---------- 11. finish 前缺少回答 ----------

    @Test
    void finishReturns409WhenAnswersMissing() throws Exception {
        long sessionId = startSession();
        submitAnswer(sessionId, "只答了第一题");

        mockMvc.perform(post("/api/interviews/{id}/finish", sessionId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void finishReturns404WhenSessionNotFound() throws Exception {
        mockMvc.perform(post("/api/interviews/{id}/finish", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ---------- 9 + 12. 第 20 题结束、完整 finish、重复 finish ----------

    @Test
    void lastAnswerMarksFinishedAndFinishProducesCompleteReport() throws Exception {
        long sessionId = startSession();

        // 依次答完 20 题
        for (int i = 1; i <= 20; i++) {
            boolean last = i == 20;
            MvcResult result = mockMvc.perform(post("/api/interviews/{id}/answers", sessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"answer\":\"第" + i + "题的回答内容\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.interviewFinished").value(last))
                    .andReturn();
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            boolean finished = JsonPath.parse(body).read("$.data.interviewFinished", Boolean.class);
            if (last) {
                assertThat(finished).isTrue();
                assertThat(JsonPath.parse(body).read("$.data.nextQuestion", Object.class))
                        .isNull();
            } else {
                int expectedNext = i + 1;
                Integer nextNo = JsonPath.parse(body).read("$.data.nextQuestion.questionNo");
                assertThat(nextNo).isEqualTo(expectedNext);
            }
        }

        InterviewSession session = sessionMapper.selectById(sessionId);
        assertThat(session.getCurrentQuestion()).isEqualTo(21);
        assertThat(session.getStatus()).isEqualTo("IN_PROGRESS");

        // 全部作答后再次提交应被拒绝
        mockMvc.perform(post("/api/interviews/{id}/answers", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"重复提交\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        // finish：AI 整体评价 + 报告落库 + 会话完成
        MvcResult finishResult = mockMvc.perform(
                        post("/api/interviews/{id}/finish", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.report.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.report.totalScore").value(85))
                .andExpect(jsonPath("$.data.report.summary").isNotEmpty())
                .andExpect(jsonPath("$.data.report.strengths").isArray())
                .andExpect(jsonPath("$.data.report.weakPoints").isArray())
                .andExpect(jsonPath("$.data.report.suggestions").isArray())
                .andExpect(jsonPath("$.data.report.questionEvaluations.length()").value(20))
                .andExpect(jsonPath("$.data.report.questionEvaluations[0].questionNo").value(1))
                // 测试桩 score = 5 + (questionNo % 5)，第 1 题为 6
                .andExpect(jsonPath("$.data.report.questionEvaluations[0].score").value(6))
                .andReturn();

        String body = finishResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> evaluations = JsonPath.parse(body)
                .read("$.data.report.questionEvaluations", List.class);
        assertThat(evaluations).hasSize(20);
        for (Map<String, Object> evaluation : evaluations) {
            assertThat((Integer) evaluation.get("score")).isBetween(0, 10);
            assertThat((String) evaluation.get("analysis")).isNotBlank();
        }

        // 数据库落库核验
        InterviewSession completed = sessionMapper.selectById(sessionId);
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getFinishedAt()).isNotNull();

        InterviewReport report = reportMapper.selectOne(
                new QueryWrapper<InterviewReport>().eq("session_id", sessionId));
        assertThat(report).isNotNull();
        assertThat(report.getTotalScore()).isEqualTo(85);
        assertThat(report.getSummary()).isNotBlank();

        List<InterviewAnswer> answers = answerMapper.selectList(
                new QueryWrapper<InterviewAnswer>());
        assertThat(answers).hasSize(20);
        assertThat(answers).allMatch(a -> a.getScore() != null
                && a.getScore() >= 0 && a.getScore() <= 10);

        // 12. 已完成的会话再次 finish → 409
        mockMvc.perform(post("/api/interviews/{id}/finish", sessionId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        // 已完成的会话提交回答 → 409
        mockMvc.perform(post("/api/interviews/{id}/answers", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"面试结束后再答\"}"))
                .andExpect(status().isConflict());
    }

    // ---------- 回答为空 ----------

    @Test
    void submitReturns400WhenAnswerBlank() throws Exception {
        long sessionId = startSession();

        mockMvc.perform(post("/api/interviews/{id}/answers", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ---------- 辅助 ----------

    private long startSession() throws Exception {
        long resumeId = seedParsedResume();
        MvcResult result = mockMvc.perform(post("/api/interviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":" + resumeId + "}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.parse(body).read("$.data.sessionId", Long.class);
    }

    private void submitAnswer(long sessionId, String answer) throws Exception {
        mockMvc.perform(post("/api/interviews/{id}/answers", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"" + answer + "\"}"))
                .andExpect(status().isOk());
    }

    private long seedParsedResume() {
        return seedResume(Resume.STATUS_PARSED, true);
    }

    private long seedResume(String status, boolean withProfile) {
        LocalDateTime now = LocalDateTime.now();

        FileMetadata fileMetadata = new FileMetadata();
        fileMetadata.setOriginalName("seed.pdf");
        fileMetadata.setObjectKey("seed/seed.pdf");
        fileMetadata.setBucketName("interview-files");
        fileMetadata.setContentType("application/pdf");
        fileMetadata.setFileSize(1L);
        fileMetadata.setCreatedAt(now);
        fileMetadata.setUpdatedAt(now);
        fileMetadataMapper.insert(fileMetadata);

        Resume resume = new Resume();
        resume.setFileId(fileMetadata.getId());
        resume.setStatus(status);
        resume.setCreatedAt(now);
        resume.setUpdatedAt(now);
        resumeMapper.insert(resume);

        if (withProfile) {
            ResumeProfile profile = new ResumeProfile();
            profile.setResumeId(resume.getId());
            profile.setName("测试候选人");
            profile.setRawText("非 AI 集成测试的简历原文占位文本，AI 环节由确定性测试桩替代。");
            profile.setCreatedAt(now);
            profile.setUpdatedAt(now);
            resumeProfileMapper.insert(profile);
        }
        return resume.getId();
    }

    /**
     * 确定性评价测试桩：totalScore=85，20 条逐题评价，score 轮转 5-9。
     * 真实模型输出由 {@link InterviewEvaluationAiIntegrationTest} 覆盖。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeEvaluationAiConfig {

        @Bean
        @Primary
        InterviewEvaluationAiParser testInterviewEvaluationAiParser() {
            return input -> {
                List<QuestionEvaluationItem> evaluations = new ArrayList<>();
                for (int i = 1; i <= 20; i++) {
                    evaluations.add(new QuestionEvaluationItem(
                            i,
                            5 + (i % 5),
                            "第 " + i + " 题回答评价（测试桩）",
                            List.of("薄弱点 " + i),
                            List.of("建议 " + i)));
                }
                return new InterviewEvaluationAiResult(
                        85,
                        "整场面试表现整体良好（测试桩总结）。",
                        List.of("基础扎实", "表达清晰"),
                        List.of("深度不足"),
                        List.of("建议加强原理复习"),
                        evaluations);
            };
        }

    }

}
