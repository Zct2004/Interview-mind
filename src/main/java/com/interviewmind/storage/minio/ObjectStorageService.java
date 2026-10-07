package com.interviewmind.storage.minio;

import java.io.InputStream;

public interface ObjectStorageService {

    /**
     * 上传对象，objectKey 由存储层生成（UUID + 原文件扩展名），不使用原始文件名。
     *
     * @return 生成的 objectKey
     */
    String upload(String originalFileName, InputStream content, long size, String contentType);

    InputStream download(String objectName);

    void delete(String objectName);

}
