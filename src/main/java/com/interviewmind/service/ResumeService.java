package com.interviewmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.interviewmind.config.MinioProperties;
import com.interviewmind.dto.ResumeDetailResponse;
import com.interviewmind.dto.ResumeDownloadFile;
import com.interviewmind.dto.ResumeListItemResponse;
import com.interviewmind.dto.ResumeUploadResponse;
import com.interviewmind.entity.FileMetadata;
import com.interviewmind.entity.Resume;
import com.interviewmind.exception.BusinessException;
import com.interviewmind.mapper.FileMetadataMapper;
import com.interviewmind.mapper.ResumeMapper;
import com.interviewmind.storage.minio.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private static final String PDF_SUFFIX = ".pdf";

    private final ObjectStorageService objectStorageService;
    private final FileMetadataMapper fileMetadataMapper;
    private final ResumeMapper resumeMapper;
    private final MinioProperties minioProperties;

    /**
     * 简历上传：校验 → MinIO 上传 → 落库（file_metadata + resume）→ 返回 resumeId。
     * 数据库保存失败时删除 MinIO 中已上传的对象，避免产生孤儿文件。
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeUploadResponse upload(MultipartFile file) {
        validate(file);

        String originalName = file.getOriginalFilename();
        String objectKey = uploadToMinio(file, originalName);

        try {
            FileMetadata fileMetadata = saveFileMetadata(file, originalName, objectKey);
            Resume resume = saveResume(fileMetadata.getId());
            return toUploadResponse(resume, fileMetadata);
        } catch (RuntimeException e) {
            deleteQuietly(objectKey);
            throw e;
        }
    }

    public List<ResumeListItemResponse> list() {
        List<Resume> resumes = resumeMapper.selectList(
                new LambdaQueryWrapper<Resume>().orderByDesc(Resume::getId));
        if (resumes.isEmpty()) {
            return List.of();
        }
        Set<Long> fileIds = resumes.stream()
                .map(Resume::getFileId)
                .collect(Collectors.toSet());
        Map<Long, FileMetadata> metadataById = fileMetadataMapper.selectBatchIds(fileIds).stream()
                .collect(Collectors.toMap(FileMetadata::getId, Function.identity()));
        return resumes.stream()
                .map(resume -> toListItem(resume, metadataById.get(resume.getFileId())))
                .toList();
    }

    public ResumeDetailResponse detail(Long id) {
        Resume resume = getResumeOrNotFound(id);
        FileMetadata fileMetadata = getFileMetadataOrNotFound(resume.getFileId());
        return new ResumeDetailResponse(
                resume.getId(),
                resume.getFileId(),
                fileMetadata.getOriginalName(),
                fileMetadata.getObjectKey(),
                fileMetadata.getBucketName(),
                fileMetadata.getFileSize(),
                fileMetadata.getContentType(),
                resume.getStatus(),
                resume.getCreatedAt(),
                resume.getUpdatedAt());
    }

    /**
     * 打开 MinIO 对象的输入流，流的关闭由调用方（Controller 写出响应时）负责。
     */
    public ResumeDownloadFile download(Long id) {
        Resume resume = getResumeOrNotFound(id);
        FileMetadata fileMetadata = getFileMetadataOrNotFound(resume.getFileId());
        InputStream content = objectStorageService.download(fileMetadata.getObjectKey());
        return new ResumeDownloadFile(
                content,
                fileMetadata.getContentType(),
                fileMetadata.getOriginalName(),
                fileMetadata.getFileSize());
    }

    /**
     * 删除顺序：MinIO 对象（事务外）→ resume → file_metadata（同一事务）。
     * MinIO 删除成功而数据库失败时记录错误日志并重抛，由事务回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Resume resume = getResumeOrNotFound(id);
        FileMetadata fileMetadata = getFileMetadataOrNotFound(resume.getFileId());

        objectStorageService.delete(fileMetadata.getObjectKey());

        try {
            resumeMapper.deleteById(id);
            fileMetadataMapper.deleteById(fileMetadata.getId());
        } catch (RuntimeException e) {
            log.error("MinIO 对象已删除，但数据库记录删除失败，需人工核对: resumeId={}, objectKey={}",
                    id, fileMetadata.getObjectKey(), e);
            throw e;
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(PDF_SUFFIX)) {
            throw new BusinessException("仅支持 PDF 格式的简历文件");
        }
    }

    private String uploadToMinio(MultipartFile file, String originalName) {
        try (InputStream inputStream = file.getInputStream()) {
            return objectStorageService.upload(originalName, inputStream, file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new RuntimeException("读取上传文件失败: " + originalName, e);
        }
    }

    private FileMetadata saveFileMetadata(MultipartFile file, String originalName, String objectKey) {
        LocalDateTime now = LocalDateTime.now();
        FileMetadata fileMetadata = new FileMetadata();
        fileMetadata.setOriginalName(originalName);
        fileMetadata.setObjectKey(objectKey);
        fileMetadata.setBucketName(minioProperties.getBucket());
        fileMetadata.setContentType(file.getContentType());
        fileMetadata.setFileSize(file.getSize());
        fileMetadata.setCreatedAt(now);
        fileMetadata.setUpdatedAt(now);
        fileMetadataMapper.insert(fileMetadata);
        return fileMetadata;
    }

    private Resume saveResume(Long fileId) {
        LocalDateTime now = LocalDateTime.now();
        Resume resume = new Resume();
        resume.setFileId(fileId);
        resume.setStatus(Resume.STATUS_UPLOADED);
        resume.setCreatedAt(now);
        resume.setUpdatedAt(now);
        resumeMapper.insert(resume);
        return resume;
    }

    private Resume getResumeOrNotFound(Long id) {
        Resume resume = resumeMapper.selectById(id);
        if (resume == null) {
            throw new BusinessException(404, "简历不存在: " + id);
        }
        return resume;
    }

    private FileMetadata getFileMetadataOrNotFound(Long fileId) {
        FileMetadata fileMetadata = fileMetadataMapper.selectById(fileId);
        if (fileMetadata == null) {
            throw new BusinessException(404, "文件元数据不存在: " + fileId);
        }
        return fileMetadata;
    }

    private ResumeUploadResponse toUploadResponse(Resume resume, FileMetadata fileMetadata) {
        return new ResumeUploadResponse(
                resume.getId(),
                fileMetadata.getId(),
                fileMetadata.getOriginalName(),
                fileMetadata.getFileSize(),
                resume.getStatus(),
                resume.getCreatedAt());
    }

    private ResumeListItemResponse toListItem(Resume resume, FileMetadata fileMetadata) {
        return new ResumeListItemResponse(
                resume.getId(),
                resume.getFileId(),
                fileMetadata.getOriginalName(),
                fileMetadata.getFileSize(),
                fileMetadata.getContentType(),
                resume.getStatus(),
                resume.getCreatedAt());
    }

    private void deleteQuietly(String objectKey) {
        try {
            objectStorageService.delete(objectKey);
        } catch (Exception e) {
            log.warn("清理 MinIO 孤儿对象失败，需人工处理: {}", objectKey, e);
        }
    }

}
