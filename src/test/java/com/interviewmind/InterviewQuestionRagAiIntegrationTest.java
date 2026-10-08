package com.interviewmind;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import com.interviewmind.rag.InterviewMemoryRetriever;
import com.interviewmind.rag.KnowledgePointAnalyzer;
import com.interviewmind.service.InterviewQuestionService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAG 读取端真实链路验收（@Tag("ai")，仅 mvn -Pai-test test 执行）：
 * <pre>
 * 已有简历 profile + PGVector 中写入历史 Interview Memory（真实 Embedding）
 * → POST /api/interviews → 一次 similaritySearch 检索（真实查询向量化 + metadata 过滤）
 * → knowledgePoint 聚合 + 动态权重（Java 计算）
 * → RAG 上下文传入出题 Prompt → 真实 LLM 生成 20 题
 * </pre>
 * 只验证：请求成功、恰好 20 题、各字段非空、RAG 检索与权重日志真实发生；
 * 不验证 LLM 是否按权重精确分配知识点（Prompt 明确只要求定性调整）。
 * 无历史的第一轮由 InterviewSessionIntegrationTest（fake 桩）覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("ai")
class InterviewQuestionRagAiIntegrationTest {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ListAppender<ILoggingEvent> retrieverLogs = new ListAppender<>();
    private final ListAppender<ILoggingEvent> analyzerLogs = new ListAppender<>();
    private final ListAppender<ILoggingEvent> serviceLogs = new ListAppender<>();

    @BeforeEach
    void resetData() {
        answerMapper.delete(new QueryWrapper<InterviewAnswer>());
        questionMapper.delete(new QueryWrapper<InterviewQuestion>());
        reportMapper.delete(new QueryWrapper<InterviewReport>());
        sessionMapper.delete(new QueryWrapper<InterviewSession>());
        resumeProfileMapper.delete(new QueryWrapper<ResumeProfile>());
        resumeMapper.delete(new QueryWrapper<Resume>());
        fileMetadataMapper.delete(new QueryWrapper<FileMetadata>());
        jdbcTemplate.update("DELETE FROM vector_store");

        attach(retrieverLogs, InterviewMemoryRetriever.class);
        attach(analyzerLogs, KnowledgePointAnalyzer.class);
        attach(serviceLogs, InterviewQuestionService.class);
    }

    @AfterEach
    void detachLogs() {
        detach(retrieverLogs, InterviewMemoryRetriever.class);
        detach(analyzerLogs, KnowledgePointAnalyzer.class);
        detach(serviceLogs, InterviewQuestionService.class);
    }

    @Test
    void secondRoundGenerates20QuestionsWithHistoricalMemoryWeights() throws Exception {
        long resumeId = seedParsedResume();

        // 1/2/3. 历史 Interview Memory 写入 PGVector（真实 qwen3.7-text-embedding）
        List<InterviewMemory> history = List.of(
                memory(resumeId, "Redis分布式锁", MemoryType.WEAK_POINT, 4,
                        "薄弱点：Redis分布式锁——候选人对看门狗自动续期与锁误释放场景回答薄弱。"),
                memory(resumeId, "Redis分布式锁", MemoryType.WEAK_POINT, 5,
                        "薄弱点：Redis分布式锁——回答遗漏 Lua 脚本原子性释放的关键要点。"),
                memory(resumeId, "Redis分布式锁", MemoryType.ANSWER_PATTERN, 7,
                        "良好表现：能够说明 setnx 加过期时间的基本用法与超时释放思路。"),
                memory(resumeId, "JVM垃圾回收", MemoryType.WEAK_POINT, 6,
                        "薄弱点：JVM垃圾回收——对 G1 混合回收与停顿预测模型解释不完整。"),
                memory(resumeId, "JVM垃圾回收", MemoryType.ANSWER_PATTERN, 8,
                        "良好表现：清楚可达性分析与常见回收器的适用场景。"),
                memory(resumeId, "MySQL索引优化", MemoryType.ANSWER_PATTERN, 9,
                        "良好表现：能够结合联合索引最左前缀原则分析慢查询。"));
        memoryStore.saveAll(history);

        Integer seeded = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'resumeId' = ?",
                Integer.class, String.valueOf(resumeId));
        assertThat(seeded).isEqualTo(6);

        // 4/5/6/7. 开始新一轮面试：RAG 检索 → 聚合 → 动态权重 → 真实 LLM 生成 20 题
        MvcResult result = mockMvc.perform(post("/api/interviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":" + resumeId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalQuestions").value(20))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // start 响应只暴露 firstQuestion，20 题以落库快照为准
        long sessionId = JsonPath.parse(body).read("$.data.sessionId", Long.class);
        List<InterviewQuestion> questions = questionMapper.selectList(
                new QueryWrapper<InterviewQuestion>().eq("session_id", sessionId));
        assertThat(questions).hasSize(20);
        for (InterviewQuestion question : questions) {
            assertThat(question.getQuestion()).isNotBlank();
            assertThat(question.getKnowledgePoint()).isNotBlank();
            assertThat(question.getReason()).isNotBlank();
            assertThat(question.getReferenceAnswer()).isNotBlank();
            assertThat(question.getDifficulty()).isIn("EASY", "MEDIUM", "HARD");
            assertThat(question.getType()).isIn("TECHNICAL", "PRINCIPLE", "PROJECT", "SCENARIO");
        }

        // 5. RAG 检索日志：单次查询，resumeId 过滤后恰好取回 6 条历史记忆
        List<String> retrieval = messages(retrieverLogs);
        assertThat(retrieval).anyMatch(message ->
                message.contains("RAG retrieval:")
                        && message.contains("resumeId=" + resumeId)
                        && message.contains("topK=30")
                        && message.contains("retrievedCount=6"));

        // 6. 动态权重日志：Redis 弱 → 1.90，JVM → 1.20，MySQL 高分 → 0.60（后端计算）
        List<String> analysis = messages(analyzerLogs);
        assertThat(analysis).anyMatch(message ->
                message.contains("knowledgePoint=Redis分布式锁")
                        && message.contains("weakPointCount=2")
                        && message.contains("weight=1.90"));
        assertThat(analysis).anyMatch(message ->
                message.contains("knowledgePoint=JVM垃圾回收")
                        && message.contains("weight=1.20"));
        assertThat(analysis).anyMatch(message ->
                message.contains("knowledgePoint=MySQL索引优化")
                        && message.contains("weight=0.60"));

        // 7. 出题编排日志：RAG 生效，权重知识点传入
        List<String> generation = messages(serviceLogs);
        assertThat(generation).anyMatch(message ->
                message.contains("Dynamic interview question generation:")
                        && message.contains("resumeId=" + resumeId)
                        && message.contains("ragEnabled=true")
                        && message.contains("Redis分布式锁"));

        // RAG 检索不写库：历史 Memory 仍是 6 条（读端零副作用）
        Integer after = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'resumeId' = ?",
                Integer.class, String.valueOf(resumeId));
        assertThat(after).isEqualTo(6);
    }

    // ---------- 日志捕获辅助 ----------

    private void attach(ListAppender<ILoggingEvent> appender, Class<?> loggerClass) {
        appender.start();
        ((Logger) LoggerFactory.getLogger(loggerClass)).addAppender(appender);
    }

    private void detach(ListAppender<ILoggingEvent> appender, Class<?> loggerClass) {
        ((Logger) LoggerFactory.getLogger(loggerClass)).detachAppender(appender);
    }

    private static List<String> messages(ListAppender<ILoggingEvent> appender) {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            if (event.getLevel().isGreaterOrEqual(Level.INFO)) {
                messages.add(event.getFormattedMessage());
            }
        }
        return messages;
    }

    // ---------- 种子数据 ----------

    private static final String SEED_RAW_TEXT = """
            张三，3 年 Java 后端开发经验。熟悉 Java 集合与并发编程、JVM 内存模型与垃圾回收，
            熟练使用 Spring Boot、MyBatis-Plus 开发微服务，掌握 MySQL 索优化与事务隔离级别，
            在项目中使用 Redis 实现分布式缓存与分布式锁，使用 RabbitMQ 解耦订单流量，
            具备线上问题排查与性能调优经验。""";

    private long seedParsedResume() {
        LocalDateTime now = LocalDateTime.now();

        FileMetadata fileMetadata = new FileMetadata();
        fileMetadata.setOriginalName("rag-ai-seed.pdf");
        fileMetadata.setObjectKey("seed/rag-ai-seed.pdf");
        fileMetadata.setBucketName("interview-files");
        fileMetadata.setContentType("application/pdf");
        fileMetadata.setFileSize(1L);
        fileMetadata.setCreatedAt(now);
        fileMetadata.setUpdatedAt(now);
        fileMetadataMapper.insert(fileMetadata);

        Resume resume = new Resume();
        resume.setFileId(fileMetadata.getId());
        resume.setStatus(Resume.STATUS_PARSED);
        resume.setCreatedAt(now);
        resume.setUpdatedAt(now);
        resumeMapper.insert(resume);

        ResumeProfile profile = new ResumeProfile();
        profile.setResumeId(resume.getId());
        profile.setName("张三");
        profile.setRawText(SEED_RAW_TEXT);
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        resumeProfileMapper.insert(profile);

        return resume.getId();
    }

    private static InterviewMemory memory(long resumeId, String knowledgePoint,
                                           MemoryType type, int score, String content) {
        return new InterviewMemory(resumeId, 999L, 999L, type, knowledgePoint, score, content);
    }

}
