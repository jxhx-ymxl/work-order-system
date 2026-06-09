package com.workorder.common.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Issue #40: 操作日志切面注解 —— 方法执行前后对比状态，变更时自动写日志 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OrderAction {
    String action();
    String remark() default "";
}
