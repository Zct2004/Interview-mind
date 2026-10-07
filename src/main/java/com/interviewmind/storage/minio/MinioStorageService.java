package com.interviewmind.storage.minio;

import com.interviewmind.config.MinioProperties;
import com.interviewmind.exception.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.UUID;

/**
 * MinIO 对象存储实现。所有 MinIO SDK 调用只出现在本类中。
 */
@Service
@RequiredArgsConstructor
public class MinioStorageService implements ObjectStorageService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    @Override
    public String upload(String originalFileName, InputStream content, long size, String contentType) {
        String objectKey = buildObjectKey(originalFileName);
        String bucket = minioProperties.getBucket();
        ensureBucketExists(bucket);
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(content, size, -1)
                    .contentType(contentType)
                    .build());
            return objectKey;
        } catch (Exception e) {
            throw new RuntimeException("MinIO 上传对象失败: " + objectKey, e);
        }
    }

    @Override
    public InputStream download(String objectName) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            if (isNoSuchKey(e)) {
                throw new BusinessException(404, "文件对象不存在: " + objectName);
            }
            throw new RuntimeException("MinIO 下载对象失败: " + objectName, e);
        }
    }

    @Override
    public void delete(String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("MinIO 删除对象失败: " + objectName, e);
        }
    }

    private boolean isNoSuchKey(Exception e) {
        if (e instanceof ErrorResponseException errorResponse
                && errorResponse.errorResponse() != null) {
            return "NoSuchKey".equals(errorResponse.errorResponse().code());
        }
        return false;
    }

    private void ensureBucketExists(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucket)
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(bucket)
                        .build());
            }
        } catch (Exception e) {
            throw new RuntimeException("MinIO bucket 检查失败: " + bucket, e);
        }
    }

    private String buildObjectKey(String originalFileName) {
        return UUID.randomUUID() + extractExtension(originalFileName);
    }

    private String extractExtension(String originalFileName) {
        int dotIndex = originalFileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFileName.length() - 1) {
            return "";
        }
        String extension = originalFileName.substring(dotIndex + 1);
        // 只保留纯字母数字扩展名，避免文件名携带路径分隔符
        if (!extension.matches("[A-Za-z0-9]{1,10}")) {
            return "";
        }
        return "." + extension;
    }

}
