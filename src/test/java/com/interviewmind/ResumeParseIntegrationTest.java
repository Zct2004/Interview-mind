package com.interviewmind;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.interviewmind.ai.ResumeAiParser;
import com.interviewmind.ai.dto.EducationItem;
import com.interviewmind.ai.dto.ProjectExperienceItem;
import com.interviewmind.ai.dto.ResumeAiResult;
import com.interviewmind.ai.dto.WorkExperienceItem;
import com.interviewmind.config.MinioProperties;
import com.jayway.jsonpath.JsonPath;
import com.interviewmind.entity.FileMetadata;
import com.interviewmind.entity.Resume;
import com.interviewmind.entity.ResumeProfile;
import com.interviewmind.mapper.FileMetadataMapper;
import com.interviewmind.mapper.ResumeMapper;
import com.interviewmind.mapper.ResumeProfileMapper;
import com.interviewmind.storage.minio.ObjectStorageService;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 简历解析集成测试（非 AI）：真实链路 MinIO → ObjectService → PDFBox → rawText → PostgreSQL。
 * <p>
 * 解析测试使用 MinIO bucket 中的真实简历（对象名含“简历”），不依赖任何本地模拟 PDF；
 * LLM 环节由 {@link FakeAiConfig} 提供的确定性 ResumeAiParser 替代，
 * 真实 LLM 调用由 {@link ResumeAiIntegrationTest}（@Tag("ai")，-Pai-test）覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ResumeParseIntegrationTest.FakeAiConfig.class)
class ResumeParseIntegrationTest {

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
    void parseExtractsRawTextFromRealResumeInMinio() throws Exception {
        long resumeId = uploadRealResume();

        mockMvc.perform(post("/api/resumes/{id}/parse", resumeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resumeId").value(resumeId))
                .andExpect(jsonPath("$.data.profileId").isNumber())
                .andExpect(jsonPath("$.data.status").value("PARSED"));

        assertThat(resumeMapper.selectById(resumeId).getStatus()).isEqualTo("PARSED");

        ResumeProfile profile = getSingleProfile(resumeId);
        assertThat(profile.getRawText())
                .isNotBlank()
                .hasSizeGreaterThan(0)
                .contains("zhangchuntian2026@163.com")
                .contains("CET-4");
    }

    @Test
    void getProfileReturnsStructuredFieldsAfterParse() throws Exception {
        long resumeId = uploadRealResume();
        long profileId = parse(resumeId);

        mockMvc.perform(get("/api/resumes/{id}/profile", resumeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resumeId").value(resumeId))
                .andExpect(jsonPath("$.data.profileId").value(profileId))
                .andExpect(jsonPath("$.data.name").value("测试姓名"))
                .andExpect(jsonPath("$.data.phone").value("13800000000"))
                .andExpect(jsonPath("$.data.email").value("test@example.com"))
                .andExpect(jsonPath("$.data.location").value(nullValue()))
                .andExpect(jsonPath("$.data.summary").value("测试摘要"))
                .andExpect(jsonPath("$.data.education[0].school").value("测试大学"))
                .andExpect(jsonPath("$.data.education[0].degree").value("本科"))
                .andExpect(jsonPath("$.data.education[0].startDate").value("2020-09"))
                .andExpect(jsonPath("$.data.workExperience[0].company").value("测试公司"))
                .andExpect(jsonPath("$.data.workExperience[0].position").value("Java开发工程师"))
                .andExpect(jsonPath("$.data.projectExperience[0].name").value("测试项目"))
                .andExpect(jsonPath("$.data.skills[0]").value("Java"))
                .andExpect(jsonPath("$.data.skills[1]").value("Spring Boot"))
                .andExpect(jsonPath("$.data.rawText").value(containsString("zhangchuntian2026@163.com")))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists());
    }

    @Test
    void parseReturns404WhenResumeNotFound() throws Exception {
        mockMvc.perform(post("/api/resumes/{id}/parse", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void getProfileReturns404WhenResumeNotFound() throws Exception {
        mockMvc.perform(get("/api/resumes/{id}/profile", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void getProfileReturns404WhenNeverParsed() throws Exception {
        long resumeId = uploadStubPdf("never-parsed.pdf");

        mockMvc.perform(get("/api/resumes/{id}/profile", resumeId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void parseFailsWhenObjectMissingInMinio() throws Exception {
        long resumeId = uploadStubPdf("missing-object.pdf");
        FileMetadata fileMetadata = getFileMetadata(resumeId);
        objectStorageService.delete(fileMetadata.getObjectKey());

        mockMvc.perform(post("/api/resumes/{id}/parse", resumeId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        assertThat(resumeMapper.selectById(resumeId).getStatus()).isEqualTo("PARSE_FAILED");
    }

    @Test
    void parseFailsWhenPdfIsNotParsable() throws Exception {
        // 扩展名为 .pdf 但内容不是 PDF：PDFBox 解析抛错 →422 → PARSE_FAILED
        long resumeId = upload(
                "这不是真正的PDF内容".getBytes(StandardCharsets.UTF_8), "invalid.pdf");

        mockMvc.perform(post("/api/resumes/{id}/parse", resumeId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));

        assertThat(resumeMapper.selectById(resumeId).getStatus()).isEqualTo("PARSE_FAILED");
        assertThat(resumeProfileMapper.selectList(
                new QueryWrapper<ResumeProfile>().eq("resume_id", resumeId))).isEmpty();
    }

    @Test
    void reparsingUpdatesExistingProfileInsteadOfInserting() throws Exception {
        long resumeId = uploadRealResume();
        long firstProfileId = parse(resumeId);

        // 篡改 DB 中的结构化字段与 rawText：再次解析必须整体覆盖（包括覆盖为 null）
        ResumeProfile stored = getSingleProfile(resumeId);
        stored.setRawText("stale");
        stored.setPhone("11111111111");
        stored.setLocation("旧城市");
        resumeProfileMapper.updateById(stored);

        long secondProfileId = parse(resumeId);
        assertThat(secondProfileId).isEqualTo(firstProfileId);

        List<ResumeProfile> rows = resumeProfileMapper.selectList(
                new QueryWrapper<ResumeProfile>().eq("resume_id", resumeId));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRawText())
                .contains("zhangchuntian2026@163.com")
                .doesNotContain("stale");
        assertThat(rows.get(0).getPhone()).isEqualTo("13800000000");
        // stub 返回 null location，重解析后必须被覆盖为 null（updateStrategy=ALWAYS 生效）
        assertThat(rows.get(0).getLocation()).isNull();
        assertThat(resumeMapper.selectById(resumeId).getStatus()).isEqualTo("PARSED");
    }

    /**
     * 非 AI 测试的确定性 AI 结果：LLM 输出结构由真实测试（ai profile）覆盖。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FakeAiConfig {

        @Bean
        @Primary
        ResumeAiParser testResumeAiParser() {
            return rawText -> new ResumeAiResult(
                    "测试姓名",
                    "13800000000",
                    "test@example.com",
                    null,
                    "测试摘要",
                    List.of(new EducationItem("测试大学", "本科", "计算机科学与技术", "2020-09", "2024-06")),
                    List.of(new WorkExperienceItem("测试公司", "Java开发工程师", "2024-07", null, "负责后端开发")),
                    List.of(new ProjectExperienceItem("测试项目", "后端开发", "项目描述")),
                    List.of("Java", "Spring Boot"));
        }

    }

    /**
     * 在 MinIO bucket 中定位真实简历对象（对象名含“简历”）。
     */
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
        return upload(content, objectKey);
    }

    private long uploadStubPdf(String fileName) throws Exception {
        return upload("""
                %PDF-1.4
                1 0 obj<</Type/Catalog>>endobj
                trailer<</Root 1 0 R>>
                %%EOF
                """.getBytes(StandardCharsets.UTF_8), fileName);
    }

    private long upload(byte[] content, String fileName) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", fileName, MediaType.APPLICATION_PDF_VALUE, content);
        MvcResult result = mockMvc.perform(multipart("/api/resumes").file(file))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.parse(body).read("$.data.resumeId", Long.class);
    }

    private long parse(long resumeId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/resumes/{id}/parse", resumeId))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.parse(body).read("$.data.profileId", Long.class);
    }

    private ResumeProfile getSingleProfile(long resumeId) {
        List<ResumeProfile> profiles = resumeProfileMapper.selectList(
                new QueryWrapper<ResumeProfile>().eq("resume_id", resumeId));
        assertThat(profiles).hasSize(1);
        return profiles.get(0);
    }

    private FileMetadata getFileMetadata(long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        assertThat(resume).isNotNull();
        FileMetadata fileMetadata = fileMetadataMapper.selectById(resume.getFileId());
        assertThat(fileMetadata).isNotNull();
        return fileMetadata;
    }

}
