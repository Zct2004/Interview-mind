package com.interviewmind;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实链路测试（mvn -Pai-test test）：
 * 构造一次已完成的模拟面试数据（不伪造 PDF、不重造面试流程）→
 * POST /finish 触发真实 Chat 评价 → InterviewMemoryBuilder →
 * qwen3.7-text-embedding → Spring AI PgVectorStore → 相似度检索找回刚才的记忆。
 * <p>
 * 普通 mvn test 通过 surefire excludedGroups=ai 排除本类，不产生任何真实调用。
 */
@Tag("ai")
@SpringBootTest
@AutoConfigureMockMvc
class InterviewMemoryAiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(InterviewMemoryAiIntegrationTest.class);

    private static final String[] KNOWLEDGE_POINTS = {
            "Redis分布式锁", "HashMap底层原理", "MySQL索引与B+树", "Spring循环依赖",
            "JVM垃圾回收", "RocketMQ削峰填谷", "Seata分布式事务", "Sentinel限流熔断",
            "Netty粘包拆包", "Redis持久化", "MySQL事务隔离级别", "SpringBean生命周期",
            "线程池核心参数", "ConcurrentHashMap分段锁", "消息重复消费与幂等", "分布式ID生成",
            "接口限流算法", "短链系统设计", "项目难点复盘", "职业规划与学习方法"};

    /** 与知识点一一对应的回答：有答得好的、半懂的、直接承认不会的，供 AI 客观评价 */
    private static final String[] ANSWERS = {
            "我知道用 setnx 加锁，Redisson 也能用，但看门狗续期和锁释放的细节我记不太清了。",
            "HashMap 初始 16，负载因子 0.75，链表长度超过 8 且数组长度大于 64 才转红黑树。",
            "InnoDB 用 B+ 树，非叶节点存索引、叶节点形成有序链表，范围查询只需要扫叶子。",
            "这个不太清楚，我记得和 @Autowired 有关，具体三级缓存的顺序说不上来。",
            "分代回收，年轻代复制算法，老年代标记清除或整理，Full GC 会导致停顿。",
            "订单进 MQ，消费者按自己的节奏处理，把数据库写入尖峰削平。",
            "Seata 的 AT 模式记录 undo_log，回滚时按镜像数据反向补偿。",
            "这个我了解不深，限流和熔断的阈值怎么配我说不上来。",
            "粘包是因为 TCP 是流式协议，需要用固定分隔符或长度头来划分消息边界。",
            "RDB 是快照文件，AOF 是追加日志，AOF 更安全但文件更大。",
            "读未提交、读已提交、可重复读、串行化，InnoDB 默认可重复读靠 MVCC 实现。",
            "实例化后属性填充，然后 Aware 回调、BeanPostProcessor 前置、init 方法、后置处理器、销毁。",
            "coreSize、maxSize、队列、拒绝策略，队列满了才会扩到 max。",
            "JDK8 之后是数组加红黑树，用 CAS 加 synchronized 保证并发安全。",
            "消费端按业务唯一键做幂等校验，重复消息直接丢弃。",
            "雪花算法，时间戳加机器 ID 加序列号，注意时钟回拨问题。",
            "计数器、滑动窗口、漏桶和令牌桶，令牌桶允许突发流量。",
            "发号器生成短码，Redis 缓存映射，301 跳转，碰撞就换码重发。",
            "难点是热点库存超卖，最后用 Redis 预扣加 Lua 脚本原子校验解决的。",
            "这个题我没什么想法，平时没系统总结过。"};

    private static final String RESUME_RAW_TEXT = """
            张三，男，3 年 Java 后端开发经验。熟悉 Spring 全家桶、MyBatis-Plus、Redis、
            MySQL、RocketMQ 等主流技术栈。曾负责电商平台订单系统重构，使用 Redis 分布式锁
            解决库存超卖问题（Redisson + 看门狗续期），通过 RocketMQ 削峰填谷支撑大促流量；
            参与分库分表与索引优化，将核心查询从 800ms 优化到 30ms；熟悉 JVM 调优与
            常见 GC 问题排查，有 Netty 串口通信与跨域 iframe postMessage 实战经验。""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("DELETE FROM vector_store");
        answerMapper.delete(new QueryWrapper<InterviewAnswer>());
        questionMapper.delete(new QueryWrapper<InterviewQuestion>());
        reportMapper.delete(new QueryWrapper<InterviewReport>());
        sessionMapper.delete(new QueryWrapper<InterviewSession>());
        resumeProfileMapper.delete(new QueryWrapper<ResumeProfile>());
        resumeMapper.delete(new QueryWrapper<Resume>());
        fileMetadataMapper.delete(new QueryWrapper<FileMetadata>());
    }

    @Test
    void completedInterviewMemoriesReachPgVectorAndAreSearchable() throws Exception {
        long sessionId = seedInterviewData();
        long resumeId = sessionMapper.selectById(sessionId).getResumeId();

        // 1. 一次完成的模拟面试：真实 Chat 评价 + Memory 构建 + Embedding + PgVector 写入
        long startMillis = System.currentTimeMillis();
        MvcResult finishResult = mockMvc.perform(
                        post("/api/interviews/{id}/finish", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.report.questionEvaluations.length()").value(20))
                .andReturn();
        log.info("finish（含 Memory 构建 + Embedding + PGVector 写入）总耗时: {}ms",
                System.currentTimeMillis() - startMillis);
        String body = finishResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"summary\"");

        // 2/3/12. PGVector 实际写入数量（Spring AI 官方 vector_store 表）
        Integer total = countBySession(sessionId);
        log.info("PGVector 中本场面试写入数量: sessionId={}, count={}", sessionId, total);
        assertThat(total).isNotNull().isGreaterThan(0);

        // 整场总结恰好 1 条；薄弱知识点记忆至少 1 条
        Integer summaries = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'sessionId' = ?"
                        + " AND metadata->>'memoryType' = 'INTERVIEW_SUMMARY'",
                Integer.class, String.valueOf(sessionId));
        assertThat(summaries).isEqualTo(1);
        Integer weakPoints = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'sessionId' = ?"
                        + " AND metadata->>'memoryType' = 'WEAK_POINT'",
                Integer.class, String.valueOf(sessionId));
        assertThat(weakPoints).isGreaterThan(0);

        // 4/5. 按知识点相似度检索，找回刚才写入的记忆（真实 qwen3.7-text-embedding 查询向量化）
        long searchStart = System.currentTimeMillis();
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("Redis 分布式锁 看门狗 自动续期机制")
                .topK(30)
                .build());
        log.info("相似度检索耗时: {}ms, 命中 {} 条", System.currentTimeMillis() - searchStart,
                hits.size());
        assertThat(hits).isNotEmpty();
        // Memory 内容非空
        assertThat(hits).allMatch(hit -> hit.getText() != null && !hit.getText().isBlank());

        Document redisHit = hits.stream()
                .filter(hit -> hit.getText().contains("知识点：Redis分布式锁"))
                .findFirst()
                .orElse(null);
        assertThat(redisHit)
                .as("应能通过 Redis 分布式锁知识点检索回刚写入的 WEAK_POINT 记忆")
                .isNotNull();

        // 6. metadata 正确（结构化字段用于后续过滤，语义检索只依赖 text）
        Map<String, Object> metadata = redisHit.getMetadata();
        assertThat(((Number) metadata.get("resumeId")).longValue()).isEqualTo(resumeId);
        assertThat(((Number) metadata.get("sessionId")).longValue()).isEqualTo(sessionId);
        assertThat(((Number) metadata.get("questionId")).longValue()).isPositive();
        assertThat((String) metadata.get("memoryType")).isEqualTo("WEAK_POINT");
        assertThat((String) metadata.get("knowledgePoint")).contains("Redis");
        assertThat(((Number) metadata.get("score")).intValue()).isBetween(0, 10);

        // 检索结果中属于本会话的记忆数量
        long mine = hits.stream()
                .filter(hit -> hit.getMetadata().get("sessionId") != null
                        && ((Number) hit.getMetadata().get("sessionId")).longValue() == sessionId)
                .count();
        log.info("相似度检索命中的本场记忆条数: {}/{}", mine, total);
        assertThat(mine).isGreaterThan(0);
    }

    private Integer countBySession(long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'sessionId' = ?",
                Integer.class, String.valueOf(sessionId));
    }

    /** 直接构造“已完成全部作答、等待 finish”的面试数据（不伪造 PDF、不重造面试流程） */
    private long seedInterviewData() {
        LocalDateTime now = LocalDateTime.now();

        FileMetadata fileMetadata = new FileMetadata();
        fileMetadata.setOriginalName("memory-ai-seed.pdf");
        fileMetadata.setObjectKey("seed/memory-ai-seed.pdf");
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
        profile.setRawText(RESUME_RAW_TEXT);
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        resumeProfileMapper.insert(profile);

        InterviewSession session = new InterviewSession();
        session.setResumeId(resume.getId());
        session.setStatus(InterviewSession.STATUS_IN_PROGRESS);
        session.setTotalQuestions(20);
        session.setCurrentQuestion(21);
        session.setStartedAt(now);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);

        String[] difficulties = {"MEDIUM", "EASY", "MEDIUM", "MEDIUM", "HARD"};
        for (int i = 0; i < 20; i++) {
            String knowledgePoint = KNOWLEDGE_POINTS[i];

            InterviewQuestion question = new InterviewQuestion();
            question.setSessionId(session.getId());
            question.setQuestionNo(i + 1);
            question.setQuestion("请结合你的经验，说明「" + knowledgePoint + "」的原理、"
                    + "常见问题与实战要点。");
            question.setType("TECHNICAL");
            question.setDifficulty(difficulties[i % difficulties.length]);
            question.setKnowledgePoint(knowledgePoint);
            question.setReason("候选人简历中体现了「" + knowledgePoint + "」相关经历");
            question.setReferenceAnswer("参考要点：" + knowledgePoint + "的核心原理、"
                    + "使用场景、常见坑与排查思路。");
            question.setCreatedAt(now);
            question.setUpdatedAt(now);
            questionMapper.insert(question);

            InterviewAnswer answer = new InterviewAnswer();
            answer.setQuestionId(question.getId());
            answer.setAnswerText(ANSWERS[i]);
            answer.setCreatedAt(now);
            answer.setUpdatedAt(now);
            answerMapper.insert(answer);
        }
        return session.getId();
    }

}
