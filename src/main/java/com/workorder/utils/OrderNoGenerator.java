package com.workorder.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class OrderNoGenerator {

    private static final String PREFIX = "WO";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RedisTemplate<String, Object> redisTemplate;

    public String next() {
        String today = LocalDate.now().format(DATE_FMT);
        String key = "order:seq:" + today;

        Long seq = redisTemplate.opsForValue().increment(key);
        if (seq != null && seq == 1) {
            redisTemplate.expire(key, 1, TimeUnit.DAYS);
        }

        return String.format("%s-%s-%05d", PREFIX, today, seq);
    }
}
