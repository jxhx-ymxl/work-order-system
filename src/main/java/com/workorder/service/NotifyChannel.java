package com.workorder.service;

/** 通知渠道接口 —— 策略模式扩展点 */
public interface NotifyChannel {

    void send(Long userId, String title, String content);

    default String channelName() {
        return this.getClass().getSimpleName();
    }
}
