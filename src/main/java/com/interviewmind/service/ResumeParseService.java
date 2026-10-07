package com.interviewmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.interviewmind.ai.ResumeAiParser;
import com.interviewmind.ai.dto.ResumeAiResult;
import com.interviewmind.dto.ResumeParseResponse;
import com.interviewmind.dto.ResumeProfileResponse;
import com.interviewmind.entity.FileMetadata;
import com.interviewmind.entity.Resume;
import com.interviewmind.entity.ResumeProfile;
import com.interviewmind.exception.BusinessException;
import com.interviewmind.mapper.FileMetadataMapper;
import com.interviewmind.mapper.ResumeMapper;
import com.interviewmind.mapper.ResumeProfileMapper;
import com.interviewmind.parser.PdfParser;
import com.interviewmind.storage.minio.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历解析编排：MinIO 下载 → PDFBox 提取 rawText → Spring AI 结构化 → 保存 resume_profile。
 * <p>
 * 状态分步提交（PARSING → PARSED / PARSE_FAILED），下载、PDF 解析与 LLM 调用都不在数据库事务里，
 * 只有“保存 profile + 置 PARSED”这组写入使用短事务保证原子性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeParseService {

    private final ResumeMapper resumeMapper;
    private final FileMetadataMapper fileMetadataMapper;
    private final ResumeProfileMapper resumeProfileMapper;
    private final ObjectStorageService objectStorageService;
    private final PdfParser pdfParser;
    private final ResumeAiParser resumeAiParser;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 手动触发解析。重复解析允许：已存在的 resume_profile 会被 UPDATE 覆盖，不会产生第二条。
     */
    public ResumeParseResponse parse(Long resumeId) {
        Resume resume = getResumeOrNotFound(resumeId);
        FileMetadata fileMetadata = getFileMetadataOrNotFound(resume.getFileId());

        updateStatus(resume, Resume.STATUS_PARSING);

        String rawText;
        try (InputStream content = objectStorageService.download(fileMetadata.getObjectKey())) {
            rawText = pdfParser.parse(content);
        } catch (IOException e) {
            // PdfParser 不抛受检异常，此处只会是 InputStream.close() 失败，同样视为解析失败
            log.error("PDF 输入流读取或关闭失败: resumeId={}", resumeId, e);
            BusinessException failure = new BusinessException(422, "PDF 解析失败");
            markParseFailed(resume, failure);
            throw failure;
        } catch (RuntimeException e) {
            markParseFailed(resume, e);
            throw e;
        }

        ResumeAiResult aiResult;
        try {
            aiResult = resumeAiParser.parse(rawText);
        } catch (RuntimeException e) {
            markParseFailed(resume, e);
            throw e;
        }

        ResumeProfile profile;
        try {
            profile = transactionTemplate.execute(txStatus -> {
                ResumeProfile saved = upsertProfile(resumeId, rawText, aiResult);
                updateStatus(resume, Resume.STATUS_PARSED);
                return saved;
            });
        } catch (RuntimeException e) {
            markParseFailed(resume, e);
            throw e;
        }

        return new ResumeParseResponse(resumeId, profile.getId(), Resume.STATUS_PARSED);
    }

    /**
     * 查询解析结果。从未解析（或简历不存在）时返回 404。
     */
    public ResumeProfileResponse profile(Long resumeId) {
        ResumeProfile profile = resumeProfileMapper.selectOne(
                new LambdaQueryWrapper<ResumeProfile>().eq(ResumeProfile::getResumeId, resumeId));
        if (profile == null) {
            throw new BusinessException(404, "简历解析结果不存在: " + resumeId);
        }
        return new ResumeProfileResponse(
                profile.getResumeId(),
                profile.getId(),
                profile.getName(),
                profile.getPhone(),
                profile.getEmail(),
                profile.getLocation(),
                profile.getSummary(),
                toJsonNode(profile.getEducation()),
                toJsonNode(profile.getWorkExperience()),
                toJsonNode(profile.getProjectExperience()),
                toJsonNode(profile.getSkills()),
                profile.getRawText(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }

    /**
     * 一个 resume 最多一条 profile（resume_id UNIQUE）：已存在则 UPDATE，否则 INSERT。
     * 结构化字段按 AI 结果整体覆盖；列表字段序列化为 JSON 存入 JSONB。
     */
    private ResumeProfile upsertProfile(Long resumeId, String rawText, ResumeAiResult aiResult) {
        LocalDateTime now = LocalDateTime.now();
        ResumeProfile existing = resumeProfileMapper.selectOne(
                new LambdaQueryWrapper<ResumeProfile>().eq(ResumeProfile::getResumeId, resumeId));

        ResumeProfile target = existing != null ? existing : new ResumeProfile();
        target.setResumeId(resumeId);
        target.setName(aiResult.name());
        target.setPhone(aiResult.phone());
        target.setEmail(aiResult.email());
        target.setLocation(aiResult.location());
        target.setSummary(aiResult.summary());
        target.setEducation(toJson(aiResult.education()));
        target.setWorkExperience(toJson(aiResult.workExperience()));
        target.setProjectExperience(toJson(aiResult.projectExperience()));
        target.setSkills(toJson(aiResult.skills()));
        target.setRawText(rawText);
        target.setUpdatedAt(now);

        if (existing != null) {
            resumeProfileMapper.updateById(target);
        } else {
            target.setCreatedAt(now);
            resumeProfileMapper.insert(target);
        }
        return target;
    }

    /**
     * 模型可能返回 null 列表，归一化为空数组，保证 JSONB 列始终是合法 JSON。
     */
    private String toJson(Object items) {
        return objectMapper.writeValueAsString(items != null ? items : List.of());
    }

    private JsonNode toJsonNode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return objectMapper.readTree(json);
    }

    /**
     * 解析失败后置 PARSE_FAILED。状态更新本身失败时只记录日志，
     * 仍抛出原始异常，避免次要失败掩盖根因。
     */
    private void markParseFailed(Resume resume, RuntimeException cause) {
        try {
            updateStatus(resume, Resume.STATUS_PARSE_FAILED);
        } catch (RuntimeException statusError) {
            log.error("解析失败后更新简历状态为 PARSE_FAILED 时出错: resumeId={}", resume.getId(), statusError);
        }
    }

    private void updateStatus(Resume resume, String status) {
        resume.setStatus(status);
        resume.setUpdatedAt(LocalDateTime.now());
        resumeMapper.updateById(resume);
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

}
