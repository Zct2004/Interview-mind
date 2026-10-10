package com.interviewmind.asr;

import com.interviewmind.config.AsrProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DashScopeAsrResultDownloadUriTest {

    @Test
    void downloadsUsingTheExactPresignedUriWithoutReencodingQueryParameters() {
        String signedUrl = "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/result.json"
                + "?Expires=1791617419&Signature=x%2Fy%2Bz%3D&OSSAccessKeyId=test%2Fkey";
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AsrProperties properties = new AsrProperties();
        properties.setBaseUrl("https://dashscope.aliyuncs.com");
        DashScopeAsrClientImpl client = new DashScopeAsrClientImpl(
                builder.build(), properties, "test-api-key", new ObjectMapper());

        server.expect(requestTo(signedUrl))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"transcripts\":[{\"text\":\"test\"}]}", MediaType.APPLICATION_JSON));

        String body = client.downloadTranscription(signedUrl);

        assertEquals("{\"transcripts\":[{\"text\":\"test\"}]}", body);
        server.verify();
    }
}
