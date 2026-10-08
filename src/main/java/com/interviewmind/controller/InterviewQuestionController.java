package com.interviewmind.controller;

import com.interviewmind.common.ApiResponse;
import com.interviewmind.dto.InterviewQuestionResponse;
import com.interviewmind.service.InterviewQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 面试题生成接口。与简历 CRUD/解析接口解耦，独立 Controller。
 */
@RestController
@RequestMapping("/api/resumes")
@RequiredArgsConstructor
public class InterviewQuestionController {

    private final InterviewQuestionService interviewQuestionService;

    @PostMapping("/{id}/interview-questions")
    public ApiResponse<InterviewQuestionResponse> generate(@PathVariable("id") Long id) {
        return ApiResponse.success(interviewQuestionService.generate(id));
    }

}
