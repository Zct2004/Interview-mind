package com.interviewmind.asr;

import com.interviewmind.asr.dto.TaskResponse;
import com.interviewmind.asr.dto.TranscriptionResult;
import com.interviewmind.asr.dto.UploadPolicyResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DashScopeAsrDtoMappingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsSnakeCaseUploadPolicyFields() {
        String json = """
                {"request_id":"req-1","data":{"policy":"p","signature":"s","upload_dir":"dir","upload_host":"https://oss.example","oss_access_key_id":"id","x_oss_object_acl":"private","x_oss_forbid_overwrite":"true"}}
                """;

        UploadPolicyResponse response = objectMapper.readValue(json, UploadPolicyResponse.class);

        assertNotNull(response.data());
        assertEquals("dir", response.data().uploadDir());
        assertEquals("https://oss.example", response.data().uploadHost());
        assertEquals("id", response.data().ossAccessKeyId());
    }

    @Test
    void mapsSnakeCaseTaskAndTranscriptionFields() {
        String taskJson = """
                {"request_id":"req-2","output":{"task_id":"task-1","task_status":"SUCCEEDED","result":{"transcription_url":"https://result.example/transcript.json"}}}
                """;
        TaskResponse task = objectMapper.readValue(taskJson, TaskResponse.class);
        assertEquals("task-1", task.output().taskId());
        assertEquals("SUCCEEDED", task.output().taskStatus());
        assertEquals("https://result.example/transcript.json", TaskResponse.extractResultUrl(task.output().result()));

        String transcriptionJson = """
                {"file_url":"oss://audio.m4a","audio_info":{"format":"m4a","sample_rate":16000},"transcripts":[{"channel_id":0,"text":"这是一段真实识别结果格式的示例文本。"}]}
                """;
        TranscriptionResult transcription = objectMapper.readValue(transcriptionJson, TranscriptionResult.class);
        assertEquals("oss://audio.m4a", transcription.fileUrl());
        assertEquals(16000, transcription.audioInfo().sampleRate());
        assertEquals("这是一段真实识别结果格式的示例文本。", transcription.transcripts().getFirst().text());
    }
}
