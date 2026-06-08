package com.workorder.service;

import com.workorder.common.BizException;
import com.workorder.common.ErrorCode;
import com.workorder.common.enums.OrderAction;
import com.workorder.common.enums.Status;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class StateMachineValidator {

    private static final Map<Status, Set<OrderAction>> ALLOWED = Map.of(
            Status.PENDING, Set.of(OrderAction.ACCEPT, OrderAction.ASSIGN),
            Status.ACCEPTED, Set.of(OrderAction.START, OrderAction.RELEASE),
            Status.IN_PROGRESS, Set.of(OrderAction.COMPLETE),
            Status.AWAIT_APPROVAL, Set.of(OrderAction.APPROVE, OrderAction.REJECT)
    );

    public Status validate(Status current, OrderAction action) {
        Set<OrderAction> allowed = ALLOWED.get(current);
        if (allowed == null || !allowed.contains(action)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "非法的状态转移: " + current + " -> " + action);
        }
        return switch (action) {
            case ACCEPT, ASSIGN -> Status.ACCEPTED;
            case START -> Status.IN_PROGRESS;
            case COMPLETE -> Status.AWAIT_APPROVAL;
            case APPROVE -> Status.CLOSED;
            case REJECT -> Status.IN_PROGRESS;
            case RELEASE -> Status.RELEASED;
        };
    }
}
