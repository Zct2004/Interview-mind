package com.interviewmind.controller;

import com.interviewmind.common.ApiResponse;
import com.interviewmind.dto.ResumeDetailResponse;
import com.interviewmind.dto.ResumeDownloadFile;
import com.interviewmind.dto.ResumeListItemResponse;
import com.interviewmind.dto.ResumeParseResponse;
import com.interviewmind.dto.ResumeProfileResponse;
import com.interviewmind.dto.ResumeUploadResponse;
import com.interviewmind.service.ResumeParseService;
import com.interviewmind.service.ResumeService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/resumes")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;
    private final ResumeParseService resumeParseService;

    @PostMapping
    public ApiResponse<ResumeUploadResponse> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(resumeService.upload(file));
    }

    @GetMapping
    public ApiResponse<List<ResumeListItemResponse>> list() {
        return ApiResponse.success(resumeService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ResumeDetailResponse> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(resumeService.detail(id));
    }

    @GetMapping("/{id}/download")
    public void download(@PathVariable("id") Long id, HttpServletResponse response) throws IOException {
        ResumeDownloadFile file = resumeService.download(id);

        String contentType = file.contentType() != null
                ? file.contentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        response.setContentType(contentType);
        response.setContentLengthLong(file.contentLength());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString());

        try (InputStream content = file.content()) {
            content.transferTo(response.getOutputStream());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) {
        resumeService.delete(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/parse")
    public ApiResponse<ResumeParseResponse> parse(@PathVariable("id") Long id) {
        return ApiResponse.success(resumeParseService.parse(id));
    }

    @GetMapping("/{id}/profile")
    public ApiResponse<ResumeProfileResponse> profile(@PathVariable("id") Long id) {
        return ApiResponse.success(resumeParseService.profile(id));
    }

}
