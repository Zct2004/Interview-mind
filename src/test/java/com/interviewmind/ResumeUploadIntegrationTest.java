package com.interviewmind;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.interviewmind.entity.FileMetadata;
import com.interviewmind.entity.Resume;
import com.interviewmind.mapper.FileMetadataMapper;
import com.interviewmind.mapper.ResumeMapper;
import com.interviewmind.storage.minio.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 简历上传闭环集成测试：上传 → MinIO → PostgreSQL → 返回 resumeId。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResumeUploadIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResumeMapper resumeMapper;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private ObjectStorageService objectStorageService;

    @Test
    void uploadPdfStoresObjectAndCreatesResumeRecord() throws Exception {
        byte[] pdfContent = """
                %PDF-1.4
                1 0 obj<</Type/Catalog>>endobj
                trailer<</Root 1 0 R>>
                %%EOF
                """.getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "resume-test.pdf", "application/pdf", pdfContent);

        MvcResult result = mockMvc.perform(multipart("/api/resumes").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resumeId").isNumber())
                .andExpect(jsonPath("$.data.fileId").isNumber())
                .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andReturn();

        DocumentContext json = JsonPath.parse(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        long resumeId = json.read("$.data.resumeId", Long.class);
        long fileId = json.read("$.data.fileId", Long.class);

        Resume resume = resumeMapper.selectById(resumeId);
        assertThat(resume).isNotNull();
        assertThat(resume.getFileId()).isEqualTo(fileId);
        assertThat(resume.getStatus()).isEqualTo("UPLOADED");

        FileMetadata fileMetadata = fileMetadataMapper.selectById(fileId);
        assertThat(fileMetadata).isNotNull();
        assertThat(fileMetadata.getOriginalName()).isEqualTo("resume-test.pdf");
        assertThat(fileMetadata.getBucketName()).isEqualTo("interview-files");
        assertThat(fileMetadata.getFileSize()).isEqualTo(pdfContent.length);
        assertThat(fileMetadata.getObjectKey()).endsWith(".pdf");

        try (InputStream stored = objectStorageService.download(fileMetadata.getObjectKey())) {
            assertThat(stored.readAllBytes()).isEqualTo(pdfContent);
        }
    }

    @Test
    void uploadRejectsNonPdfFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", "not-a-pdf".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/resumes").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("仅支持 PDF 格式的简历文件"));
    }

    @Test
    void uploadRejectsEmptyRequest() throws Exception {
        mockMvc.perform(multipart("/api/resumes"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

}
