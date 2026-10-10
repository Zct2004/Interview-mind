package com.interviewmind.asr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * transcription_url 指向的转写结果 JSON（结构依据官方文档"异步调用识别结果说明"：
 * file_url / audio_info / transcripts[].text；sentences、words 等嵌套字段超出本模块
 * 当前需要，交由 ignoreUnknown 忽略，不做臆测建模）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TranscriptionResult(
        @JsonProperty("file_url") String fileUrl,
        @JsonProperty("audio_info") AudioInfo audioInfo,
        @JsonProperty("transcripts") List<Transcript> transcripts) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AudioInfo(
            @JsonProperty("format") String format,
            @JsonProperty("sample_rate") Integer sampleRate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transcript(
            @JsonProperty("channel_id") Integer channelId,
            @JsonProperty("text") String text) {
    }

}
