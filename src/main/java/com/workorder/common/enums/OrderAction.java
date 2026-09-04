package com.workorder.common.enums;

public enum OrderAction {

    ACCEPT,
    START,
    COMPLETE,
    APPROVE,
    REJECT,
    ASSIGN,
    RELEASE,

    /** 管理员接管升级工单：ESCALATED_ADMIN -> IN_PROGRESS（需 order:manage） */
    MANAGE,

    /** 系统管理员强制关闭升级工单：ESCALATED_ADMIN -> CLOSED */
    CLOSE

}
