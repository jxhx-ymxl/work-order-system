package com.workorder.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 预算值的构造期校验。
 *
 * <p>为什么值得单独测：`HttpURLConnection.setReadTimeout(0)` 的语义是**无限等待**（不是"不等"），
 * 于是一个 {@link Duration#ZERO} 的读取超时会把"有上限的口径"变成"没有上限"——
 * 这类错误在运行期表现为"偶发卡死"，最难排查。
 */
@DisplayName("预算与超时值的构造期校验")
class AgentLimitsTest {

    private static AgentLimits defaults() {
        return AgentLimits.s1Defaults();
    }

    @Test
    @DisplayName("读取超时为 0 → 构造即拒绝（0 在 HttpURLConnection 里等于无限等待）")
    void zeroReadTimeout_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> defaults().withModelReadTimeout(Duration.ZERO));
    }

    @Test
    @DisplayName("连接超时为 0 → 构造即拒绝")
    void zeroConnectTimeout_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> defaults().withModelConnectTimeout(Duration.ZERO));
    }

    @Test
    @DisplayName("负的读取超时 → 构造即拒绝")
    void negativeReadTimeout_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> defaults().withModelReadTimeout(Duration.ofMillis(-1)));
    }
}
