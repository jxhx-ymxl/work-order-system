package com.workorder.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class OrderNoGeneratorTest {

    @Autowired
    private OrderNoGenerator orderNoGenerator;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Test
    @DisplayName("生成3个编号——序号依次递增、格式正确")
    void testNext_incrementingSequence() {
        String no1 = orderNoGenerator.next();
        String no2 = orderNoGenerator.next();
        String no3 = orderNoGenerator.next();

        String today = LocalDate.now().format(DATE_FMT);

        assertTrue(no1.matches("WO-" + today + "-\\d{5}"));
        assertTrue(no2.matches("WO-" + today + "-\\d{5}"));
        assertTrue(no3.matches("WO-" + today + "-\\d{5}"));

        String seq1 = no1.substring(no1.lastIndexOf('-') + 1);
        String seq2 = no2.substring(no2.lastIndexOf('-') + 1);
        String seq3 = no3.substring(no3.lastIndexOf('-') + 1);

        int s1 = Integer.parseInt(seq1);
        int s2 = Integer.parseInt(seq2);
        int s3 = Integer.parseInt(seq3);

        assertEquals(s1 + 1, s2);
        assertEquals(s2 + 1, s3);
    }

    @Test
    @DisplayName("Redis key 存在且有 TTL")
    void testRedisKeyAndTtl() {
        orderNoGenerator.next();
        String today = LocalDate.now().format(DATE_FMT);
        String key = "order:seq:" + today;

        Object val = redisTemplate.opsForValue().get(key);
        assertNotNull(val);

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "TTL should be positive but was " + ttl);
        assertTrue(ttl <= TimeUnit.DAYS.toSeconds(1), "TTL should not exceed 1 day but was " + ttl);
    }

    @Test
    @DisplayName("INCR 自增——连续调用后 key 的值等于调用次数")
    void testIncrValueMatchesCallCount() {
        String today = LocalDate.now().format(DATE_FMT);
        String key = "order:seq:" + today;
        redisTemplate.delete(key);

        for (int i = 1; i <= 5; i++) {
            String no = orderNoGenerator.next();
            String expectedSuffix = String.format("%05d", i);
            assertTrue(no.endsWith(expectedSuffix), "Expected suffix " + expectedSuffix + " but got " + no);
        }
    }
}
