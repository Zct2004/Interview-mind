package com.interviewmind.controller;

import com.interviewmind.common.ApiResponse;
import com.interviewmind.dto.InterviewAnswerRequest;
import com.interviewmind.dto.InterviewAnswerResponse;
import com.interviewmind.dto.InterviewCurrentQuestionResponse;
import com.interviewmind.dto.InterviewFinishResponse;
import com.interviewmind.dto.InterviewStartRequest;
import com.interviewmind.dto.InterviewStartResponse;
import com.interviewmind.service.InterviewSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟面试流程接口：开始面试 → 当前题 → 提交回答 → 结束面试。
 * Controller 只负责 HTTP 参数与响应，业务流程在 Service。
 */
@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
public class InterviewSessionController {

    private final InterviewSessionService interviewSessionService;

    @PostMapping
    public ApiResponse<InterviewStartResponse> start(@RequestBody InterviewStartRequest request) {
        return ApiResponse.success(interviewSessionService.start(request));
    }

    @GetMapping("/{sessionId}/current-question")
    public ApiResponse<InterviewCurrentQuestionResponse> currentQuestion(
            @PathVariable("sessionId") Long sessionId) {
        return ApiResponse.success(interviewSessionService.currentQuestion(sessionId));
    }

    @PostMapping("/{sessionId}/answers")
    public ApiResponse<InterviewAnswerResponse> submitAnswer(
            @PathVariable("sessionId") Long sessionId,
            @RequestBody InterviewAnswerRequest request) {
        return ApiResponse.success(interviewSessionService.submitAnswer(sessionId, request));
    }

    @PostMapping("/{sessionId}/finish")
    public ApiResponse<InterviewFinishResponse> finish(@PathVariable("sessionId") Long sessionId) {
        return ApiResponse.success(interviewSessionService.finish(sessionId));
    }

    /** 删除一轮已完成的模拟面试及其题目、回答、报告和 RAG 记忆。 */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> delete(@PathVariable("sessionId") Long sessionId) {
        interviewSessionService.deleteSession(sessionId);
        return ApiResponse.success(null);
    }

}
