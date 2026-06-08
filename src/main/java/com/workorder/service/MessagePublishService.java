package com.workorder.service;

public interface MessagePublishService {

    void sendReleaseCheck(Long orderId);

    void sendSlaEscalation(Long orderId);
}
