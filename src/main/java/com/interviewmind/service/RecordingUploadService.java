package com.interviewmind.service;

import com.interviewmind.config.MinioProperties;
import com.interviewmind.entity.FileMetadata;
import com.interviewmind.exception.BusinessException;
import com.interviewmind.mapper.FileMetadataMapper;
import com.interviewmind.storage.minio.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 面试录音上传：校验文件后存入 MinIO，并保存文件元数据。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingUploadService {

    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of(".m4a", ".mp3", ".wav", ".aac", ".flac", ".ogg", ".opus", ".webm", ".mp4");

    private final ObjectStorageService objectStorageService;
    private final FileMetadataMapper fileMetadataMapper;
    private final MinioProperties minioProperties;

    @Transactional(rollbackFor = Exception.class)
    public RecordingUploadResponse upload(MultipartFile file) {
        validate(file);

        String originalName = file.getOriginalFilename().trim();
        String contentSha256 = sha256(file);
        FileMetadata duplicate = findDuplicate(originalName, file.getSize(), contentSha256);
        if (duplicate != null) {
            log.info("检测到重复录音，复用已有对象: objectKey={}", duplicate.getObjectKey());
            return new RecordingUploadResponse(
                    duplicate.getObjectKey(), duplicate.getOriginalName(), duplicate.getFileSize(),
                    duplicate.getContentType(), duplicate.getId(), true, duplicate.getCreatedAt());
        }

        String objectKey;
        try (InputStream input = file.getInputStream()) {
            objectKey = objectStorageService.upload(originalName, input, file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new BusinessException(400, "读取录音文件失败，请重新选择文件");
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            FileMetadata metadata = new FileMetadata();
            metadata.setOriginalName(originalName);
            metadata.setObjectKey(objectKey);
            metadata.setBucketName(minioProperties.getBucket());
            metadata.setContentType(file.getContentType());
            metadata.setFileSize(file.getSize());
            metadata.setContentSha256(contentSha256);
            metadata.setCreatedAt(now);
            metadata.setUpdatedAt(now);
            fileMetadataMapper.insert(metadata);
            return new RecordingUploadResponse(
                    objectKey, originalName, file.getSize(), file.getContentType(), metadata.getId(), false, now);
        } catch (RuntimeException e) {
            try {
                objectStorageService.delete(objectKey);
            } catch (RuntimeException cleanupError) {
                log.error("录音元数据保存失败且 MinIO 清理失败，需人工核对: objectKey={}", objectKey, cleanupError);
            }
            throw e;
        }
    }

    private FileMetadata findDuplicate(String originalName, long fileSize, String contentSha256) {
        FileMetadata exact = fileMetadataMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileMetadata>()
                .eq(FileMetadata::getContentSha256, contentSha256)
                .last("LIMIT 1"));
        if (exact != null) return exact;

        // 兼容旧数据：只对同名同大小文件回查 MinIO 并计算哈希，不扫描/下载整个桶。
        List<FileMetadata> candidates = fileMetadataMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileMetadata>()
                        .eq(FileMetadata::getOriginalName, originalName)
                        .eq(FileMetadata::getFileSize, fileSize));
        for (FileMetadata candidate : candidates) {
            if (candidate.getContentSha256() != null) continue;
            try (InputStream existing = objectStorageService.download(candidate.getObjectKey())) {
                String existingHash = sha256(existing);
                candidate.setContentSha256(existingHash);
                candidate.setUpdatedAt(LocalDateTime.now());
                fileMetadataMapper.updateById(candidate);
                if (contentSha256.equals(existingHash)) return candidate;
            } catch (Exception e) {
                log.warn("无法校验旧录音是否重复，继续按新文件处理: objectKey={}", candidate.getObjectKey(), e);
            }
        }
        return null;
    }

    private String sha256(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            return sha256(input);
        } catch (IOException e) {
            throw new BusinessException(400, "读取录音文件失败，请重新选择文件");
        }
    }

    private String sha256(InputStream input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        } catch (IOException e) {
            throw new BusinessException(400, "读取录音文件失败，请重新选择文件");
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请先选择要上传的录音文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(400, "录音文件不能超过 100 MB");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank() || originalName.length() > 255) {
            throw new BusinessException(400, "录音文件名无效或过长");
        }
        String lowerName = originalName.toLowerCase(Locale.ROOT);
        boolean allowed = ALLOWED_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
        if (!allowed) {
            throw new BusinessException(400, "不支持该音频格式，请上传 M4A、MP3、WAV、AAC、FLAC、OGG、OPUS、WEBM 或 MP4");
        }
    }

    public record RecordingUploadResponse(
            String objectKey, String originalName, long fileSize, String contentType,
            Long fileId, boolean duplicate, LocalDateTime createdAt) {}
}
