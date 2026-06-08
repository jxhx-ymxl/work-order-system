package com.workorder.service;

import com.workorder.common.BizException;
import com.workorder.common.enums.OrderAction;
import com.workorder.common.enums.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StateMachineValidatorTest {

    private final StateMachineValidator validator = new StateMachineValidator();

    @Test
    @DisplayName("PENDING + ACCEPT → ACCEPTED")
    void testPendingAccept() {
        assertEquals(Status.ACCEPTED, validator.validate(Status.PENDING, OrderAction.ACCEPT));
    }

    @Test
    @DisplayName("PENDING + ASSIGN → ACCEPTED")
    void testPendingAssign() {
        assertEquals(Status.ACCEPTED, validator.validate(Status.PENDING, OrderAction.ASSIGN));
    }

    @Test
    @DisplayName("ACCEPTED + START → IN_PROGRESS")
    void testAcceptedStart() {
        assertEquals(Status.IN_PROGRESS, validator.validate(Status.ACCEPTED, OrderAction.START));
    }

    @Test
    @DisplayName("ACCEPTED + RELEASE → RELEASED")
    void testAcceptedRelease() {
        assertEquals(Status.RELEASED, validator.validate(Status.ACCEPTED, OrderAction.RELEASE));
    }

    @Test
    @DisplayName("IN_PROGRESS + COMPLETE → AWAIT_APPROVAL")
    void testInProgressComplete() {
        assertEquals(Status.AWAIT_APPROVAL, validator.validate(Status.IN_PROGRESS, OrderAction.COMPLETE));
    }

    @Test
    @DisplayName("AWAIT_APPROVAL + APPROVE → CLOSED")
    void testAwaitApprovalApprove() {
        assertEquals(Status.CLOSED, validator.validate(Status.AWAIT_APPROVAL, OrderAction.APPROVE));
    }

    @Test
    @DisplayName("AWAIT_APPROVAL + REJECT → IN_PROGRESS")
    void testAwaitApprovalReject() {
        assertEquals(Status.IN_PROGRESS, validator.validate(Status.AWAIT_APPROVAL, OrderAction.REJECT));
    }

    // ---- 非法转移 ----

    @Test
    @DisplayName("PENDING + START → 抛异常")
    void testPendingStart_illegal() {
        BizException ex = assertThrows(BizException.class,
                () -> validator.validate(Status.PENDING, OrderAction.START));
        assertTrue(ex.getMessage().contains("非法的状态转移"));
    }

    @Test
    @DisplayName("CLOSED + REJECT → 抛异常")
    void testClosedReject_illegal() {
        BizException ex = assertThrows(BizException.class,
                () -> validator.validate(Status.CLOSED, OrderAction.REJECT));
        assertTrue(ex.getMessage().contains("非法的状态转移"));
    }

    @Test
    @DisplayName("CLOSED + ACCEPT → 抛异常")
    void testClosedAccept_illegal() {
        BizException ex = assertThrows(BizException.class,
                () -> validator.validate(Status.CLOSED, OrderAction.ACCEPT));
        assertTrue(ex.getMessage().contains("非法的状态转移"));
    }

    @Test
    @DisplayName("IN_PROGRESS + APPROVE → 抛异常")
    void testInProgressApprove_illegal() {
        BizException ex = assertThrows(BizException.class,
                () -> validator.validate(Status.IN_PROGRESS, OrderAction.APPROVE));
        assertTrue(ex.getMessage().contains("非法的状态转移"));
    }
}
