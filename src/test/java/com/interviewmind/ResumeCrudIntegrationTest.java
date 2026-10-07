package com.interviewmind;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jayway.jsonpath.JsonPath;
import com.interviewmind.entity.FileMetadata;
import com.interviewmind.entity.Resume;
import com.interviewmind.exception.BusinessException;
import com.interviewmind.mapper.FileMetadataMapper;
import com.interviewmind.mapper.ResumeMapper;
import com.interviewmind.storage.minio.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 简历管理 CRUD 集成测试，使用真实 PostgreSQL + MinIO。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResumeCrudIntegrationTest {

    private static final byte[] PDF_CONTENT = """
            %PDF-1.4
            1 0 obj<</Type/Catalog>>endobj
            trailer<</Root 1 0 R>>
            %%EOF
            """.getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResumeMapper resumeMapper;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private ObjectStorageService objectStorageService;

    @BeforeEach
    void resetData() {
        resumeMapper.delete(new QueryWrapper<Resume>());
        fileMetadataMapper.delete(new QueryWrapper<FileMetadata>());
    }

    @Test
    void listReturnsUploadedResumes() throws Exception {
        long resumeId = uploadResume("简历A.pdf");

        mockMvc.perform(get("/api/resumes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].resumeId").value(resumeId))
                .andExpect(jsonPath("$.data[0].originalName").value("简历A.pdf"))
                .andExpect(jsonPath("$.data[0].contentType").value("application/pdf"))
                .andExpect(jsonPath("$.data[0].fileSize").value(PDF_CONTENT.length))
                .andExpect(jsonPath("$.data[0].status").value("UPLOADED"))
                .andExpect(jsonPath("$.data[0].createdAt").exists());
    }

    @Test
    void listReturnsEmptyArrayWhenNoResume() throws Exception {
        mockMvc.perform(get("/api/resumes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void detailReturnsResume() throws Exception {
        long resumeId = uploadResume("detail.pdf");

        mockMvc.perform(get("/api/resumes/{id}", resumeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resumeId").value(resumeId))
                .andExpect(jsonPath("$.data.originalName").value("detail.pdf"))
                .andExpect(jsonPath("$.data.objectKey").isNotEmpty())
                .andExpect(jsonPath("$.data.bucketName").value("interview-files"))
                .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andExpect(jsonPath("$.data.updatedAt").exists());
    }

    @Test
    void detailReturns404WhenResumeNotFound() throws Exception {
        mockMvc.perform(get("/api/resumes/{id}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void downloadStreamsPdfWithContentTypeAndFilename() throws Exception {
        long resumeId = uploadResume("简历下载测试.pdf");

        MvcResult result = mockMvc.perform(get("/api/resumes/{id}/download", resumeId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(PDF_CONTENT);
    }

    @Test
    void downloadReturns404WhenResumeNotFound() throws Exception {
        mockMvc.perform(get("/api/resumes/{id}/download", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void downloadReturns404WhenObjectMissingInMinio() throws Exception {
        long resumeId = uploadResume("missing-object.pdf");
        FileMetadata fileMetadata = getFileMetadata(resumeId);
        objectStorageService.delete(fileMetadata.getObjectKey());

        mockMvc.perform(get("/api/resumes/{id}/download", resumeId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void deleteRemovesDatabaseRowsAndMinioObject() throws Exception {
        long resumeId = uploadResume("delete-me.pdf");
        FileMetadata fileMetadata = getFileMetadata(resumeId);
        String objectKey = fileMetadata.getObjectKey();

        mockMvc.perform(delete("/api/resumes/{id}", resumeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"));

        assertThat(resumeMapper.selectById(resumeId)).isNull();
        assertThat(fileMetadataMapper.selectById(fileMetadata.getId())).isNull();
        assertThatThrownBy(() -> objectStorageService.download(objectKey))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
    }

    @Test
    void deleteReturns404WhenResumeNotFound() throws Exception {
        mockMvc.perform(delete("/api/resumes/{id}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    private long uploadResume(String fileName) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", fileName, MediaType.APPLICATION_PDF_VALUE, PDF_CONTENT);
        MvcResult result = mockMvc.perform(multipart("/api/resumes").file(file))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.parse(body).read("$.data.resumeId", Long.class);
    }

    private FileMetadata getFileMetadata(long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        assertThat(resume).isNotNull();
        FileMetadata fileMetadata = fileMetadataMapper.selectById(resume.getFileId());
        assertThat(fileMetadata).isNotNull();
        return fileMetadata;
    }

}
