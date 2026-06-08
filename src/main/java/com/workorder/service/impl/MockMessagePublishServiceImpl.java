package com.workorder.service.impl;

import com.workorder.service.MessagePublishService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MockMessagePublishServiceImpl implements MessagePublishService {

    @Override
    public void sendReleaseCheck(Long orderId) {
        log.info("[MOCK-MQ] 发送超时释放检查消息, orderId={}", orderId);
    }

    @Override
    public void sendSlaEscalation(Long orderId) {
        log.info("[MOCK-MQ] 发送SLA升级通知消息, orderId={}", orderId);
    }
}
