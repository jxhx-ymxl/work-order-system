package com.workorder.agent;

/**
 * **取消信号**（§11-3 / §4.2）：调用方置位，模型读取循环与调查循环各自检查。
 *
 * <p><b>为什么不用 `Thread.interrupt()`</b>（**有意不采用**）：这条链路上的等待点是
 * **阻塞式 `read`**，它**不响应 interrupt**——interrupt 只会把标志置上，`read` 仍然要等到
 * 数据到达或**读超时**才返回。既然如此，与其让调用方以为"interrupt 就等于取消"，
 * 不如把真实机制写明白：**取消延迟上界 = 当前轮读超时**（该值已被剩余运行预算收敛，见 §4.1）。
 *
 * <p>真正缩短它只有一条路：把客户端换成**可中断 IO**（`SocketChannel` / NIO）——属 S4 之后的选型，
 * 不是本轮的范围（本轮把"能取消"做实，并把上界写进契约）。
 */
public final class Cancellation {

    /** 永不取消（既有调用点默认用它；**不要对它调 {@link #cancel}**）。 */
    public static final Cancellation NONE = new Cancellation();

    private volatile boolean cancelled;
    private volatile String reason;

    public static Cancellation create() {
        return new Cancellation();
    }

    public void cancel(String reason) {
        if (!cancelled) {
            this.reason = reason;
            this.cancelled = true;
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public String reason() {
        return reason == null ? "用户取消" : reason;
    }
}
