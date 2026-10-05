package com.workorder.agent;

import java.util.regex.Pattern;

/**
 * 数据外发前的脱敏（`docs/AGENT-PLAN.md` §4.4 定稿）。
 *
 * <p>两类规则刻意分开：
 * <ul>
 *   <li>{@link #redactText(String)}：自由文本上的**形状唯一**的模式——手机号、邮箱、身份证。
 *       它们不需要语义判断，正则替换不会误伤正常内容。</li>
 *   <li>{@link #maskName(String)}：只用于**结构化姓名字段**（如处理人显示名）。</li>
 * </ul>
 *
 * <p><b>为什么不对自由文本做姓名脱敏</b>：中文姓名没有可判定的词边界。本机实测：
 * {@code ([一-龥])[一-龥]{1,2}(?=句读|结尾)} 这类正则在 4 句常见文案上命中 6 个普通词
 * （"工单已处理。"→`已处理`、"请核实情况。"→`实情况`…），却漏掉了真名（"张伟接单了。"未命中）。
 * 也就是说它是**既伤内容又不保安全**。宁可承认"自由文本里的姓名拦不住"，也不做会把内容改坏的脱敏——
 * 代价已写进 §4.4 与 D77。
 *
 * <p>方法都是静态的：它没有状态，也不该被替换成"某个注入的实现"（S1 不需要为未来留扩展点）。
 */
public final class SensitiveDataRedactor {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(\\d{6})\\d{8}(\\d{3}[0-9Xx])(?!\\d)");

    private SensitiveDataRedactor() {
    }

    /** 手机号 / 邮箱 / 身份证。 */
    public static String redactText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = PHONE.matcher(text).replaceAll("$1****$2");
        masked = EMAIL.matcher(masked).replaceAll("$1***$2");
        masked = ID_CARD.matcher(masked).replaceAll("$1********$2");
        return masked;
    }

    /** 结构化姓名：**保留首字**，其余掩码（{@code 张伟} → {@code 张*}；复姓只留首字，首版不识别复姓）。 */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String trimmed = name.trim();
        if (trimmed.length() == 1) {
            return trimmed;
        }
        return trimmed.charAt(0) + "*";
    }
}
