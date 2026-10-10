package com.interviewmind.controller;

import com.interviewmind.asr.AsrService;
import com.interviewmind.asr.dto.AsrResult;
import com.interviewmind.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsrControllerTest {

    @Test
    void delegatesObjectKeyToAsrServiceWithoutPersistingTranscript() {
        AsrService service = mock(AsrService.class);
        AsrResult result = new AsrResult("task-123", "转写文本");
        when(service.transcribe("recordings/sample.m4a")).thenReturn(result);
        AsrController controller = new AsrController(service);

        ResponseEntity<ApiResponse<AsrResult>> response = controller.transcribe("recordings/sample.m4a");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(result, response.getBody().data());
        verify(service).transcribe("recordings/sample.m4a");
    }

    @Test
    void rejectsBlankObjectKeyWithoutCallingAsrService() {
        AsrService service = mock(AsrService.class);
        AsrController controller = new AsrController(service);

        ResponseEntity<ApiResponse<AsrResult>> response = controller.transcribe("  ");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().code());
    }
}
