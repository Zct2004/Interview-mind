package com.interviewmind;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.interviewmind.config.MinioProperties;
import com.interviewmind.entity.FileMetadata;
import com.interviewmind.entity.Resume;
import com.interviewmind.entity.ResumeProfile;
import com.interviewmind.mapper.FileMetadataMapper;
import com.interviewmind.mapper.ResumeMapper;
import com.interviewmind.mapper.ResumeProfileMapper;
import com.interviewmind.storage.minio.ObjectStorageService;
import com.jayway.jsonpath.JsonPath;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 LLM 面试题生成集成测试：
 * MinIO 真实简历（张鸿添简历.pdf）→ PDFBox → Spring AI 解析 → Spring AI 生成 20 道面试题。
 * <p>
 * 默认 mvn test 通过 surefire excludedGroups=ai 排除本类（不消耗 token）；
 * 运行 mvn -Pai-test test 时纳入执行，直接复用 application.yml 中已配置的
 * spring.ai.openai（不读环境变量、不改任何 AI 配置）。
 * 断言围绕“结构性约束”（数量、字段非空、枚举合法、不重复），不写死具体题目内容。
 */
@Tag("ai")
@SpringBootTest
@AutoConfigureMockMvc
class InterviewQuestionAiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResumeMapper resumeMapper;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private ResumeProfileMapper resumeProfileMapper;

    @Autowired
    private ObjectStorageService objectStorageService;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private MinioProperties minioProperties;

    @BeforeEach
    void resetData() {
        resumeProfileMapper.delete(new QueryWrapper<ResumeProfile>());
        resumeMapper.delete(new QueryWrapper<Resume>());
        fileMetadataMapper.delete(new QueryWrapper<FileMetadata>());
    }

    @Test
    void generate20QuestionsFromRealResumeWithRealLlm() throws Exception {
        long resumeId = uploadRealResume();
        parse(resumeId);

        long startMillis = System.currentTimeMillis();
        MvcResult result = mockMvc.perform(post("/api/resumes/{id}/interview-questions", resumeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resumeId").value(resumeId))
                .andReturn();
        long costMillis = System.currentTimeMillis() - startMillis;
        org.slf4j.LoggerFactory.getLogger(InterviewQuestionAiIntegrationTest.class)
                .info("真实 LLM 生成 20 道面试题耗时: {}ms", costMillis);

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> questions = JsonPath.parse(body).read("$.data.questions", List.class);

        // 1. 数量恰好 20
        assertThat(questions).hasSize(20);

        Set<String> difficulties = new HashSet<>();
        Set<String> types = new HashSet<>();
        Set<String> uniqueQuestions = new HashSet<>();

        for (Map<String, Object> question : questions) {
            // 2/3. question 与 knowledgePoint 非空
            assertThat((String) question.get("question")).isNotBlank();
            assertThat((String) question.get("knowledgePoint")).isNotBlank();
            // 7. referenceAnswer 与出题依据非空
            assertThat((String) question.get("referenceAnswer")).isNotBlank();
            assertThat((String) question.get("reason")).isNotBlank();
            // 4. difficulty 合法
            String difficulty = (String) question.get("difficulty");
            assertThat(difficulty).isIn("EASY", "MEDIUM", "HARD");
            difficulties.add(difficulty);
            // 5. type 合法
            String type = (String) question.get("type");
            assertThat(type).isIn("TECHNICAL", "PRINCIPLE", "PROJECT", "SCENARIO");
            types.add(type);
            // 6. 没有完全重复的题目
            uniqueQuestions.add(((String) question.get("question")).trim());
        }

        // 无明显重复（原样去重后仍应为 20）
        assertThat(uniqueQuestions).hasSize(20);
        // 难度与题型应有分布（宽松断言，仅要求都出现过，具体 5/10/5 由 prompt 约束）
        assertThat(difficulties).contains("EASY", "MEDIUM", "HARD");
        assertThat(types).contains("TECHNICAL", "PROJECT");
        org.slf4j.LoggerFactory.getLogger(InterviewQuestionAiIntegrationTest.class)
                .info("难度分布={}, 题型分布={}", difficulties, types);
    }

    private void parse(long resumeId) throws Exception {
        mockMvc.perform(post("/api/resumes/{id}/parse", resumeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARSED"));
        assertThat(resumeMapper.selectById(resumeId).getStatus()).isEqualTo("PARSED");
        ResumeProfile profile = resumeProfileMapper.selectOne(
                new QueryWrapper<ResumeProfile>().eq("resume_id", resumeId));
        assertThat(profile).isNotNull();
        assertThat(profile.getRawText()).isNotBlank().contains("zhangchuntian2026@163.com");
    }

    private String findRealResumeKey() {
        try {
            for (Result<Item> result : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .build())) {
                String objectName = result.get().objectName();
                if (objectName.contains("简历")) {
                    return objectName;
                }
            }
        } catch (Exception e) {
            throw new AssertionError("列举 MinIO 对象失败", e);
        }
        throw new AssertionError("MinIO bucket 中未找到真实简历对象（名称需包含“简历”）");
    }

    private long uploadRealResume() throws Exception {
        String objectKey = findRealResumeKey();
        byte[] content;
        try (InputStream in = objectStorageService.download(objectKey)) {
            content = in.readAllBytes();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", objectKey, MediaType.APPLICATION_PDF_VALUE, content);
        MvcResult result = mockMvc.perform(multipart("/api/resumes").file(file))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.parse(body).read("$.data.resumeId", Long.class);
    }

}
