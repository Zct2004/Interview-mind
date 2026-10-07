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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 LLM 集成测试：MinIO 真实简历 → PDFBox → Spring AI → OpenAI → ResumeProfile。
 * <p>
 * 默认 mvn test 通过 surefire excludedGroups=ai 排除本类（不消耗 token）；
 * 运行 mvn -Pai-test test 时纳入执行；同时要求环境变量 OPENAI_API_KEY 存在，
 * 否则由 {@link EnabledIfEnvironmentVariable} 跳过。
 * 断言保持宽松，不因 LLM 输出的轻微波动失败。
 */
@Tag("ai")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
class ResumeAiIntegrationTest {

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
    void parseRealResumeWithRealLlmProducesStructuredProfile() throws Exception {
        long resumeId = uploadRealResume();

        mockMvc.perform(post("/api/resumes/{id}/parse", resumeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("PARSED"));

        assertThat(resumeMapper.selectById(resumeId).getStatus()).isEqualTo("PARSED");

        ResumeProfile profile = getSingleProfile(resumeId);

        // rawText 来自真实 PDF，稳定内容必须保留
        assertThat(profile.getRawText())
                .isNotBlank()
                .hasSizeGreaterThan(0)
                .contains("zhangchuntian2026@163.com")
                .contains("CET-4");

        // 姓名若被模型识别则必须合理；不写死具体值，避免输出波动导致失败
        if (profile.getName() != null) {
            assertThat(profile.getName()).isNotBlank();
        }

        // 结构化结果存在（模型可能返回空数组，但字段必须是数组而非 null）
        mockMvc.perform(get("/api/resumes/{id}/profile", resumeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.rawText").value(containsString("zhangchuntian2026@163.com")))
                .andExpect(jsonPath("$.data.education").isArray())
                .andExpect(jsonPath("$.data.workExperience").isArray())
                .andExpect(jsonPath("$.data.projectExperience").isArray())
                .andExpect(jsonPath("$.data.skills").isArray());
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

    private ResumeProfile getSingleProfile(long resumeId) {
        List<ResumeProfile> profiles = resumeProfileMapper.selectList(
                new QueryWrapper<ResumeProfile>().eq("resume_id", resumeId));
        assertThat(profiles).hasSize(1);
        return profiles.get(0);
    }

}
