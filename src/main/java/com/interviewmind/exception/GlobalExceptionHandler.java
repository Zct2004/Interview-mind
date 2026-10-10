package com.interviewmind.exception;

import com.interviewmind.common.ApiResponse;
import com.interviewmind.asr.AsrException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        return ResponseEntity.status(e.getCode())
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "请求体缺失或格式错误"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "缺少必填参数: " + e.getParameterName()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "缺少必填文件参数: " + e.getRequestPartName()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413)
                .body(ApiResponse.error(413, "文件大小超过限制，最大 20MB"));
    }
    @ExceptionHandler(AsrException.class)
    public ResponseEntity<ApiResponse<Void>> handleAsrException(AsrException e) {
        // 只向客户端暴露稳定的阶段码，不回传远端响应正文、凭证或异常消息。
        log.error("ASR 转写失败 code={} taskId={} remoteCode={}",
                e.getCode(), e.getTaskId(), e.getRemoteCode(), e);
        return ResponseEntity.status(502)
                .body(ApiResponse.error(502, "ASR 转写失败，阶段=" + e.getCode().name()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("接口处理异常", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(500, "系统内部错误"));
    }

}
