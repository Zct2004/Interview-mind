package com.interviewmind;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.interviewmind.ai.InterviewQuestionAiParser;
import com.interviewmind.ai.dto.InterviewQuestionAiResult;
import com.interviewmind.ai.dto.QuestionItem;
import com.interviewmind.entity.FileMetadata;
import com.interviewmind.entity.Resume;
import com.interviewmind.entity.ResumeProfile;
import com.interviewmind.mapper.FileMetadataMapper;
import com.interviewmind.mapper.ResumeMapper;
import com.interviewmind.mapper.ResumeProfileMapper;
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
 * 面试题生成集成测试（非 AI）：验证 API 契约与状态前置校验。
 * <p>
 * AI 环节由 {@link FakeQuestionAiConfig} 的确定性 InterviewQuestionAiParser 替代，
 * 普通 mvn test 不消耗 token；真实 LLM 调用由 {@link InterviewQuestionAiIntegrationTest}
 * （@Tag("ai")，-Pai-test）覆盖。本阶段不落库，测试直接在数据库中构造
 * resume/profile 行（不调用 parse，不涉及 PDF 与 MinIO）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
        InterviewQuestionIntegrationTest.FakeQuestionAiConfig.class,
        // Memory 出口测试桩：generate() 的 RAG 检索在普通测试中同样不触发真实 Embedding
        FakeInterviewMemoryStoreConfig.class
})
class InterviewQuestionIntegrationTest {

    private static final String PLACEHOLDER_RAW_TEXT =
            "非 AI 集成测试的简历原文占位文本：AI 环节由确定性测试桩替代，真实链路由 ai profile 测试覆盖。";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResumeMapper resumeMapper;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private ResumeProfileMapper resumeProfileMapper;

    @BeforeEach
    void resetData() {
        resumeProfileMapper.delete(new QueryWrapper<ResumeProfile>());
        resumeMapper.delete(new QueryWrapper<Resume>());
        fileMetadataMapper.delete(new QueryWrapper<FileMetadata>());
    }

    @Test
    void generateReturnsExactly20QuestionsForParsedResume() throws Exception {
        long resumeId = seedParsedResume();

        MvcResult result = mockMvc.perform(post("/api/resumes/{id}/interview-questions", resumeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resumeId").value(resumeId))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> questions = JsonPath.parse(body).read("$.data.questions", List.class);

        assertThat(questions).hasSize(20);
        for (Map<String, Object> question : questions) {
            assertThat((String) question.get("question")).isNotBlank();
            assertThat((String) question.get("knowledgePoint")).isNotBlank();
            assertThat((String) question.get("reason")).isNotBlank();
            assertThat((String) question.get("referenceAnswer")).isNotBlank();
            assertThat((String) question.get("difficulty")).isIn("EASY", "MEDIUM", "HARD");
            assertThat((String) question.get("type")).isIn("TECHNICAL", "PRINCIPLE", "PROJECT", "SCENARIO");
        }
    }

    @Test
    void generateReturns404WhenResumeNotFound() throws Exception {
        mockMvc.perform(post("/api/resumes/{id}/interview-questions", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void generateReturns409WhenResumeNotParsed() throws Exception {
        long resumeId = seedResume(Resume.STATUS_UPLOADED, false);

        mockMvc.perform(post("/api/resumes/{id}/interview-questions", resumeId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void generateReturns409WhenProfileMissingAfterParsed() throws Exception {
        // 状态为 PARSED 但没有 profile 行：同样视为不可生成
        long resumeId = seedResume(Resume.STATUS_PARSED, false);

        mockMvc.perform(post("/api/resumes/{id}/interview-questions", resumeId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    /**
     * 非 AI 测试的确定性 AI 结果：恰好 20 道题，难度 5/10/5。
     * 真实模型输出由 {@link InterviewQuestionAiIntegrationTest} 覆盖。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeQuestionAiConfig {

        @Bean
        @Primary
        InterviewQuestionAiParser testInterviewQuestionAiParser() {
            return (rawText, ragContext) -> {
                String[] difficulties = {
                        "EASY", "EASY", "EASY", "EASY", "EASY",
                        "MEDIUM", "MEDIUM", "MEDIUM", "MEDIUM", "MEDIUM",
                        "MEDIUM", "MEDIUM", "MEDIUM", "MEDIUM", "MEDIUM",
                        "HARD", "HARD", "HARD", "HARD", "HARD"};
                String[] types = {"TECHNICAL", "PRINCIPLE", "PROJECT", "SCENARIO"};
                List<QuestionItem> questions = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    questions.add(new QuestionItem(
                            "测试题目 " + (i + 1),
                            types[i % types.length],
                            difficulties[i],
                            "测试知识点 " + (i + 1),
                            "测试出题依据 " + (i + 1),
                            "测试参考答案 " + (i + 1)));
                }
                return new InterviewQuestionAiResult(questions);
            };
        }

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
            profile.setRawText(PLACEHOLDER_RAW_TEXT);
            profile.setCreatedAt(now);
            profile.setUpdatedAt(now);
            resumeProfileMapper.insert(profile);
        }
        return resume.getId();
    }

}
