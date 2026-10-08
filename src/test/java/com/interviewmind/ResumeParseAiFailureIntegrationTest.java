package com.interviewmind;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.interviewmind.ai.ResumeAiParser;
import com.interviewmind.config.MinioProperties;
import com.interviewmind.entity.FileMetadata;
import com.interviewmind.entity.Resume;
import com.interviewmind.entity.ResumeProfile;
import com.interviewmind.exception.BusinessException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 环节失败的负向集成测试：PDFBox 提取 rawText 成功，但 LLM 调用失败时
 * Resume 必须置为 PARSE_FAILED、不落 profile、接口返回 502。
 * <p>
 * AI 由 {@link FailingAiConfig} 的确定性失败桩替代，避免普通测试消耗 token。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ResumeParseAiFailureIntegrationTest.FailingAiConfig.class)
class ResumeParseAiFailureIntegrationTest {

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
    void parseMarksResumeFailedWhenAiCallFails() throws Exception {
        long resumeId = uploadRealResume();

        mockMvc.perform(post("/api/resumes/{id}/parse", resumeId))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502));

        assertThat(resumeMapper.selectById(resumeId).getStatus()).isEqualTo("PARSE_FAILED");
        assertThat(resumeProfileMapper.selectList(
                new QueryWrapper<ResumeProfile>().eq("resume_id", resumeId))).isEmpty();
    }

    /**
     * 在 MinIO bucket 中定位真实简历对象（对象名含“简历”），PDFBox 能正常提取 rawText，
     * 保证失败点落在 AI 环节而非 PDF 环节。
     */
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

    /**
     * 确定性失败桩：rawText 一到 AI 环节立即抛出 502。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingAiConfig {

        @Bean
        @Primary
        ResumeAiParser failingResumeAiParser() {
            return rawText -> {
                throw new BusinessException(502, "AI 简历结构化失败（测试桩）");
            };
        }

    }

}
