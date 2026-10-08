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
import com.interviewmind.memory.InterviewMemoryStore;
import com.interviewmind.memory.dto.InterviewMemory;
import com.interviewmind.memory.dto.InterviewMemory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Interview Memory 普通集成测试（mvn test，零真实 LLM / Embedding 调用）：
 * 题目与评价均来自确定性测试桩，Memory 出口由 {@link FakeInterviewMemoryStoreConfig} 捕获。
 * 真实 Embedding + PgVector 链路由 {@link InterviewMemoryAiIntegrationTest}（@Tag("ai")）覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
        InterviewQuestionIntegrationTest.FakeQuestionAiConfig.class,
        InterviewSessionIntegrationTest.FakeEvaluationAiConfig.class,
        FakeInterviewMemoryStoreConfig.class
})
class InterviewMemoryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InterviewMemoryStore memoryStore;

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

    private FakeInterviewMemoryStoreConfig.CapturingMemoryStore store() {
        return (FakeInterviewMemoryStoreConfig.CapturingMemoryStore) memoryStore;
    }

    @BeforeEach
    void resetData() {
        store().clear();
        store().noFail();
        answerMapper.delete(new QueryWrapper<InterviewAnswer>());
        questionMapper.delete(new QueryWrapper<InterviewQuestion>());
        reportMapper.delete(new QueryWrapper<InterviewReport>());
        sessionMapper.delete(new QueryWrapper<InterviewSession>());
        resumeProfileMapper.delete(new QueryWrapper<ResumeProfile>());
        resumeMapper.delete(new QueryWrapper<Resume>());
        fileMetadataMapper.delete(new QueryWrapper<FileMetadata>());
    }

    @Test
    void finishBuildsAndIndexesMemoriesWithoutRealEmbedding() throws Exception {
        long resumeId = seedParsedResume();
        long sessionId = startAndAnswerAll(resumeId);

        mockMvc.perform(post("/api/interviews/{id}/finish", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.report.totalScore").value(85));

        // 一次完成的面试 → 生成 Memory（测试桩捕获，证明没有真实 Embedding 调用）
        List<InterviewMemory> memories = store().saved();
        assertThat(memories).isNotEmpty();

        // 内容非空、长度封顶、类型合法、归属正确
        assertThat(memories).allSatisfy(memory -> {
            assertThat(memory.content()).isNotBlank();
            assertThat(memory.content().length())
                    .isLessThanOrEqualTo(InterviewMemoryBuilderCaps.MAX_CONTENT);
            assertThat(memory.memoryType()).isIn(MemoryType.values());
            assertThat(memory.resumeId()).isEqualTo(resumeId);
            assertThat(memory.sessionId()).isEqualTo(sessionId);
        });

        // 确定性测试桩数据：20 WEAK_POINT + 8 ANSWER_PATTERN + 1 INTERVIEW_SUMMARY = 29
        Map<MemoryType, Long> counts = new EnumMap<>(MemoryType.class);
        memories.forEach(m -> counts.merge(m.memoryType(), 1L, Long::sum));
        assertThat(counts.get(MemoryType.WEAK_POINT)).isEqualTo(20L);
        assertThat(counts.get(MemoryType.ANSWER_PATTERN)).isEqualTo(8L);
        assertThat(counts.get(MemoryType.INTERVIEW_SUMMARY)).isEqualTo(1L);

        // 整场总结含总评分；题目级记忆带知识点与 0-10 分
        InterviewMemory summary = memories.stream()
                .filter(m -> m.memoryType() == MemoryType.INTERVIEW_SUMMARY).findFirst().orElseThrow();
        assertThat(summary.score()).isEqualTo(85);
        assertThat(summary.content()).contains("总评分 85/100");
        assertThat(memories.stream()
                .filter(m -> m.memoryType() == MemoryType.WEAK_POINT)).allSatisfy(weakPoint -> {
            assertThat(weakPoint.knowledgePoint()).isNotBlank();
            assertThat(weakPoint.score()).isBetween(0, 10);
            assertThat(weakPoint.questionId()).isNotNull();
        });

        // 无重复（类型 + 知识点 + 内容）
        Set<String> keys = new HashSet<>();
        for (InterviewMemory memory : memories) {
            keys.add(memory.memoryType() + "|" + memory.knowledgePoint() + "|" + memory.content());
        }
        assertThat(keys).hasSize(memories.size());
    }

    @Test
    void memoryIndexingFailureKeepsInterviewCompleted() throws Exception {
        long resumeId = seedParsedResume();
        long sessionId = startAndAnswerAll(resumeId);

        store().failWith(new RuntimeException("vector store unavailable（测试模拟）"));
        try {
            // 向量写入失败：面试主流程必须保持成功且 COMPLETED，不能回滚
            mockMvc.perform(post("/api/interviews/{id}/finish", sessionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.data.report.totalScore").value(85));
        } finally {
            store().noFail();
        }

        assertThat(store().saved()).isEmpty();

        InterviewSession session = sessionMapper.selectById(sessionId);
        assertThat(session.getStatus()).isEqualTo("COMPLETED");
        assertThat(session.getFinishedAt()).isNotNull();
        InterviewReport report = reportMapper.selectOne(
                new QueryWrapper<InterviewReport>().eq("session_id", sessionId));
        assertThat(report).isNotNull();
        assertThat(report.getTotalScore()).isEqualTo(85);
    }

    // ---------- 辅助 ----------

    /** 上限值与主代码保持一致，避免为测试暴露包私有常量 */
    private static final class InterviewMemoryBuilderCaps {
        private static final int MAX_CONTENT = 600;
    }

    private long startAndAnswerAll(long resumeId) throws Exception {
        var startResult = mockMvc.perform(post("/api/interviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":" + resumeId + "}"))
                .andExpect(status().isOk())
                .andReturn();
        String startBody = startResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        long sessionId = com.jayway.jsonpath.JsonPath.parse(startBody)
                .read("$.data.sessionId", Long.class);

        for (int i = 1; i <= 20; i++) {
            mockMvc.perform(post("/api/interviews/{id}/answers", sessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"answer\":\"第 " + i + " 题的模拟回答内容\"}"))
                    .andExpect(status().isOk());
        }
        return sessionId;
    }

    private long seedParsedResume() {
        LocalDateTime now = LocalDateTime.now();

        FileMetadata fileMetadata = new FileMetadata();
        fileMetadata.setOriginalName("memory-seed.pdf");
        fileMetadata.setObjectKey("seed/memory-seed.pdf");
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
        profile.setName("记忆测试候选人");
        profile.setRawText("普通测试占位简历原文，AI 环节由确定性测试桩替代。");
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        resumeProfileMapper.insert(profile);

        return resume.getId();
    }

}
