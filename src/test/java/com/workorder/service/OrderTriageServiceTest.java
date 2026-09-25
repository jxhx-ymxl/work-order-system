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
    @DisplayName("llm.api.url 为空 → 抛 TriageUnavailableException（不再静默降级成「判成其他」）")
    void testTriage_urlEmpty_shouldFallbackSilently() {
        OrderTriageService service = createService("", "", 5000);

        assertThrows(com.workorder.common.TriageUnavailableException.class,
                () -> service.triage("空调报修", "3楼空调不制冷"));
    }

    @Test
    @DisplayName("llm.api.url 为 null → 抛 TriageUnavailableException")
    void testTriage_urlNull_shouldFallbackSilently() {
        OrderTriageService service = createService(null, null, 5000);

        assertThrows(com.workorder.common.TriageUnavailableException.class,
                () -> service.triage("请假申请", "年假5天"));
    }

    @Test
    @DisplayName("LLM正常返回 → 正确解析type和priority")
    void testTriage_normalResponse_shouldParseCorrectly() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"type\\":\\"NETWORK\\",\\"priority\\":1}"
                    }
                  }]
                }""";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        TriageResult result = service.triage("服务器宕机", "线上服务器无法访问");

        assertEquals("NETWORK", result.getSuggestedType());
        assertEquals(1, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("LLM返回DORM类型 → 正确解析")
    void testTriage_leaveType_shouldParseCorrectly() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"type\\":\\"DORM\\",\\"priority\\":0}"
                    }
                  }]
                }""";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        TriageResult result = service.triage("请假", "年假申请");

        assertEquals("DORM", result.getSuggestedType());
        assertEquals(0, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("LLM返回UTILITY类型 → 正确解析")
    void testTriage_reimburseType_shouldParseCorrectly() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"type\\":\\"UTILITY\\",\\"priority\\":0}"
                    }
                  }]
                }""";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        TriageResult result = service.triage("差旅报销", "北京出差住宿费");

        assertEquals("UTILITY", result.getSuggestedType());
        assertEquals(0, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("HTTP超时 → 抛 TriageUnavailableException（P5 步骤 3：失败不再被当成结论）")
    void testTriage_timeout_shouldFallback() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"));

        assertThrows(com.workorder.common.TriageUnavailableException.class,
                () -> service.triage("空调报修", "3楼空调不制冷"));
    }

    @Test
    @DisplayName("LLM返回乱码 → 抛 TriageUnavailableException")
    void testTriage_garbledResponse_shouldFallback() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = "此响应不是合法的 JSON 字节流 xfffd xfffd xfffd garbled data ???";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        assertThrows(com.workorder.common.TriageUnavailableException.class,
                () -> service.triage("空调报修", "3楼空调不制冷"));
    }

    @Test
    @DisplayName("LLM返回空响应体 → 抛 TriageUnavailableException")
    void testTriage_emptyResponse_shouldFallback() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(""));

        assertThrows(com.workorder.common.TriageUnavailableException.class,
                () -> service.triage("空调报修", "3楼空调不制冷"));
    }

    @Test
    @DisplayName("LLM返回非法type值 → 抛 TriageUnavailableException（消费端会按失败进重试账本）")
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

        assertThrows(com.workorder.common.TriageUnavailableException.class,
                () -> service.triage("黑客攻击", "尝试注入"));
    }

    @Test
    @DisplayName("LLM返回非法priority值 → 抛 TriageUnavailableException")
    void testTriage_invalidPriority_shouldFallback() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"type\\":\\"NETWORK\\",\\"priority\\":999}"
                    }
                  }]
                }""";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        assertThrows(com.workorder.common.TriageUnavailableException.class,
                () -> service.triage("空调报修", "3楼空调不制冷"));
    }

    @Test
    @DisplayName("LLM返回JSON中混合额外文本 → 正确提取JSON部分")
    void testTriage_jsonWithExtraText_shouldExtractCorrectly() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "根据分析，这是一条报修工单。{\\"type\\":\\"NETWORK\\",\\"priority\\":1}"
                    }
                  }]
                }""";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        TriageResult result = service.triage("空调报修", "3楼空调不制冷");

        assertEquals("NETWORK", result.getSuggestedType());
        assertEquals(1, result.getSuggestedPriority());
    }

    @Test
    @DisplayName("网络连接失败 → 抛 TriageUnavailableException")
    void testTriage_connectionRefused_shouldFallback() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Connection refused"));

        assertThrows(com.workorder.common.TriageUnavailableException.class,
                () -> service.triage("空调报修", "3楼空调不制冷"));
    }

    @Test
    @DisplayName("HTTP 500错误 → 抛 TriageUnavailableException")
    void testTriage_serverError_shouldFallback() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new org.springframework.web.client.HttpServerErrorException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error"));

        assertThrows(com.workorder.common.TriageUnavailableException.class,
                () -> service.triage("空调报修", "3楼空调不制冷"));
    }

    @Test
    @DisplayName("TriageResult.fallback() 静态工厂方法")
    void testTriageResult_fallback() {
        TriageResult fallback = TriageResult.fallback();
        assertEquals("OTHER", fallback.getSuggestedType());
        assertEquals(0, fallback.getSuggestedPriority());
    }

    @Test
    @DisplayName("P5 收口：模型给了 reason → 解析出来（信息不足的依据要能留痕）")
    void testTriage_parsesReason() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"type\\":\\"OTHER\\",\\"priority\\":0,\\"reason\\":\\"依据不足：未说明设备、现象与位置\\"}"
                    }
                  }]
                }""";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        TriageResult result = service.triage("有点问题", "就是不太好用");

        assertEquals("OTHER", result.getSuggestedType());
        assertEquals(0, result.getSuggestedPriority());
        assertEquals("依据不足：未说明设备、现象与位置", result.getReason());
    }

    @Test
    @DisplayName("P5 收口：响应里没有 reason → 不影响判定（老响应格式仍然可用）")
    void testTriage_reasonAbsent_isTolerated() {
        OrderTriageServiceImpl service = createService("http://mock-llm/api/chat", "sk-test", 5000);

        String mockResponse = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"type\\":\\"NETWORK\\",\\"priority\\":1}"
                    }
                  }]
                }""";
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(mockResponse));

        TriageResult result = service.triage("教学楼三楼断网", "整层楼都连不上校园网");

        assertEquals("NETWORK", result.getSuggestedType());
        assertEquals(1, result.getSuggestedPriority());
        assertNull(result.getReason(), "没有 reason 字段时应为 null，而不是抛异常或填默认文案");
    }
}
