package com.workorder.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** §4.4 的四种脱敏模式 + "自由文本不做姓名替换"这条刻意取舍。 */
@DisplayName("数据外发脱敏规则")
class SensitiveDataRedactorTest {

    @Test
    @DisplayName("手机号 / 邮箱 / 身份证在自由文本上被掩码")
    void redactsPatternsWithUnambiguousShape() {
        String raw = "手机 13812345678，邮箱 zhangsan@example.com，身份证 110101199003071234";

        String masked = SensitiveDataRedactor.redactText(raw);

        assertEquals("手机 138****5678，邮箱 z***@example.com，身份证 110101********1234", masked);
    }

    @Test
    @DisplayName("非手机号的数字串不被误伤（订单号 / 时间戳原样保留）")
    void leavesUnrelatedNumbersAlone() {
        assertEquals("工单 WO-20260607-00001，时间 2026-10-05T09:12:00",
                SensitiveDataRedactor.redactText("工单 WO-20260607-00001，时间 2026-10-05T09:12:00"));
    }

    @Test
    @DisplayName("姓名只在结构化字段上掩码，自由文本不做姓名替换（避免把普通词改坏）")
    void masksNameOnlyOnStructuredFields() {
        assertEquals("张*", SensitiveDataRedactor.maskName("张伟"));
        assertEquals("欧*", SensitiveDataRedactor.maskName("欧阳娜娜"));
        assertEquals("工单已处理", SensitiveDataRedactor.redactText("工单已处理"));
        assertFalse(SensitiveDataRedactor.redactText("工单已处理").contains("*"));
        assertEquals("李娜已接单", SensitiveDataRedactor.redactText("李娜已接单"));
    }
}
