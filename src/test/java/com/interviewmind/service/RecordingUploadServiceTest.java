package com.interviewmind.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.interviewmind.config.MinioProperties;
import com.interviewmind.entity.FileMetadata;
import com.interviewmind.exception.BusinessException;
import com.interviewmind.mapper.FileMetadataMapper;
import com.interviewmind.storage.minio.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecordingUploadServiceTest {

    @Test
    void rejectsSameContentBeforeWritingToMinio() {
        ObjectStorageService storage = mock(ObjectStorageService.class);
        FileMetadataMapper metadataMapper = mock(FileMetadataMapper.class);
        MinioProperties minioProperties = mock(MinioProperties.class);
        FileMetadata existing = new FileMetadata();
        existing.setId(12L);
        existing.setOriginalName("existing-recording.m4a");
        existing.setObjectKey("existing-object.m4a");
        existing.setFileSize(4L);
        existing.setContentSha256("9f64a747e1b97f131fabb6b447296c9b6f0201e79e4e9f8c3b7f7e2b0e3f6b0f");
        when(metadataMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        RecordingUploadService service = new RecordingUploadService(storage, metadataMapper, minioProperties);
        MockMultipartFile duplicate = new MockMultipartFile(
                "file", "renamed-copy.m4a", "audio/mp4", new byte[] {1, 2, 3, 4});

        BusinessException error = assertThrows(BusinessException.class, () -> service.upload(duplicate));

        assertEquals(409, error.getCode());
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("existing-recording.m4a"));
        verifyNoInteractions(storage);
    }
}
