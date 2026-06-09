package com.workorder.service;

import com.workorder.common.dto.TriageResult;
import com.workorder.service.impl.OrderTriageServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderTriageServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private OrderTriageServiceImpl createService(String apiUrl, String apiKey, int timeout) {
        OrderTriageServiceImpl service = new OrderTriageServiceImpl(apiUrl, apiKey, timeout);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        return service;
    }

    @Test
    @DisplayName("llm.api.url为空 → 静默降级返回默认值")
    void testTriage_urlEmpty_shouldFallbackSilently() {
        OrderTriageService service = createService("", "", 5000);

        TriageResult result = service.triage("空调报修", "3楼空调不制冷");

        assertEquals("OTHER", result.getSuggestedType());
        assertEquals(0, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("llm.api.url为null → 静默降级返回默认值")
    void testTriage_urlNull_shouldFallbackSilently() {
        OrderTriageService service = createService(null, null, 5000);

        TriageResult result = service.triage("请假申请", "年假5天");

        assertEquals("OTHER", result.getSuggestedType());
        assertEquals(0, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("LLM正常返回 → 正确解析type和priority")
    void testTriage_normalResponse_shouldParseCorrectly() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"type\\":\\"REPAIR\\",\\"priority\\":1}"
                    }
                  }]
                }""";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        TriageResult result = service.triage("服务器宕机", "线上服务器无法访问");

        assertEquals("REPAIR", result.getSuggestedType());
        assertEquals(1, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("LLM返回LEAVE类型 → 正确解析")
    void testTriage_leaveType_shouldParseCorrectly() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"type\\":\\"LEAVE\\",\\"priority\\":0}"
                    }
                  }]
                }""";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        TriageResult result = service.triage("请假", "年假申请");

        assertEquals("LEAVE", result.getSuggestedType());
        assertEquals(0, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("LLM返回REIMBURSE类型 → 正确解析")
    void testTriage_reimburseType_shouldParseCorrectly() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"type\\":\\"REIMBURSE\\",\\"priority\\":0}"
                    }
                  }]
                }""";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        TriageResult result = service.triage("差旅报销", "北京出差住宿费");

        assertEquals("REIMBURSE", result.getSuggestedType());
        assertEquals(0, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("HTTP超时 → 返回默认值不抛异常")
    void testTriage_timeout_shouldFallback() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"));

        TriageResult result = service.triage("空调报修", "3楼空调不制冷");

        assertEquals("OTHER", result.getSuggestedType());
        assertEquals(0, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("LLM返回乱码 → 返回默认值不抛异常")
    void testTriage_garbledResponse_shouldFallback() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = "此响应不是合法的 JSON 字节流 xfffd xfffd xfffd garbled data ???";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        TriageResult result = service.triage("空调报修", "3楼空调不制冷");

        assertEquals("OTHER", result.getSuggestedType());
        assertEquals(0, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("LLM返回空响应体 → 返回默认值")
    void testTriage_emptyResponse_shouldFallback() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(""));

        TriageResult result = service.triage("空调报修", "3楼空调不制冷");

        assertEquals("OTHER", result.getSuggestedType());
        assertEquals(0, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("LLM返回非法type值 → 返回默认值")
    void testTriage_invalidType_shouldFallback() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"type\\":\\"HACKING\\",\\"priority\\":1}"
                    }
                  }]
                }""";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        TriageResult result = service.triage("黑客攻击", "尝试注入");

        assertEquals("OTHER", result.getSuggestedType());
        assertEquals(0, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("LLM返回非法priority值 → 返回默认值")
    void testTriage_invalidPriority_shouldFallback() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"type\\":\\"REPAIR\\",\\"priority\\":999}"
                    }
                  }]
                }""";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        TriageResult result = service.triage("空调报修", "3楼空调不制冷");

        assertEquals("OTHER", result.getSuggestedType());
        assertEquals(0, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("LLM返回JSON中混合额外文本 → 正确提取JSON部分")
    void testTriage_jsonWithExtraText_shouldExtractCorrectly() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "根据分析，这是一条报修工单。{\\"type\\":\\"REPAIR\\",\\"priority\\":1}"
                    }
                  }]
                }""";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        TriageResult result = service.triage("空调报修", "3楼空调不制冷");

        assertEquals("REPAIR", result.getSuggestedType());
        assertEquals(1, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("网络连接失败 → 返回默认值不抛异常")
    void testTriage_connectionRefused_shouldFallback() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Connection refused"));

        TriageResult result = service.triage("空调报修", "3楼空调不制冷");

        assertEquals("OTHER", result.getSuggestedType());
        assertEquals(0, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("HTTP 500错误 → 返回默认值不抛异常")
    void testTriage_serverError_shouldFallback() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new org.springframework.web.client.HttpServerErrorException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error"));

        TriageResult result = service.triage("空调报修", "3楼空调不制冷");

        assertEquals("OTHER", result.getSuggestedType());
        assertEquals(0, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("TriageResult.fallback() 静态工厂方法")
    void testTriageResult_fallback() {
        TriageResult fallback = TriageResult.fallback();
        assertEquals("OTHER", fallback.getSuggestedType());
        assertEquals(0, fallback.getSuggestedPriority());
    }
}
