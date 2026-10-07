package com.interviewmind.dto;

import java.io.InputStream;

/**
 * 下载所需的文件流及其响应头信息，由 Service 交给 Controller 写出。
 */
public record ResumeDownloadFile(
        InputStream content,
        String contentType,
        String fileName,
        long contentLength) {
}
