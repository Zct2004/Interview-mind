package com.interviewmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.interviewmind.dto.RecordingTranscriptImportRequest;
import com.interviewmind.entity.RecordingTranscript;
import com.interviewmind.mapper.RecordingTranscriptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RecordingTranscriptService {

    private final RecordingTranscriptMapper mapper;

    @Transactional
    public RecordingTranscript saveAsrResult(String objectKey, String taskId, String transcriptText) {
        return save(objectKey, taskId, transcriptText, "ASR_SUCCESS");
    }

    @Transactional
    public RecordingTranscript importText(RecordingTranscriptImportRequest request) {
        if (request == null || request.objectKey() == null || request.objectKey().isBlank()
                || request.transcriptText() == null || request.transcriptText().isBlank()) {
            throw new IllegalArgumentException("objectKey 和 transcriptText 不能为空");
        }
        return save(request.objectKey().trim(), request.taskId(), request.transcriptText(), "MANUAL_IMPORT");
    }

    public RecordingTranscript getByObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey 不能为空");
        }
        return mapper.selectOne(new LambdaQueryWrapper<RecordingTranscript>()
                .eq(RecordingTranscript::getObjectKey, objectKey.trim()));
    }

    private RecordingTranscript save(String objectKey, String taskId, String transcriptText, String status) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey 不能为空");
        }
        if (transcriptText == null || transcriptText.isBlank()) {
            throw new IllegalArgumentException("转写文本不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        RecordingTranscript existing = mapper.selectOne(new LambdaQueryWrapper<RecordingTranscript>()
                .eq(RecordingTranscript::getObjectKey, objectKey.trim()));

        RecordingTranscript record = existing == null ? new RecordingTranscript() : existing;
        record.setObjectKey(objectKey.trim());
        record.setTaskId(taskId);
        record.setTranscriptText(transcriptText);
        record.setCharacterCount(transcriptText.length());
        record.setStatus(status);
        record.setUpdatedAt(now);

        if (existing == null) {
            record.setCreatedAt(now);
            mapper.insert(record);
        } else {
            mapper.updateById(record);
        }
        return record;
    }
}
