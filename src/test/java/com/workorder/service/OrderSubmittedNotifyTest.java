package com.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.dto.SubmitOrderReq;
import com.workorder.entity.ConsumeRecord;
import com.workorder.entity.EventOutbox;
import com.workorder.entity.MessageRetry;
import com.workorder.entity.Notification;
import com.workorder.entity.Role;
import com.workorder.entity.User;
import com.workorder.entity.UserRole;
import com.workorder.entity.WorkOrder;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.ConsumeRecordMapper;
import com.workorder.mapper.EventOutboxMapper;
import com.workorder.mapper.MessageRetryMapper;
import com.workorder.mapper.NotificationMapper;
import com.workorder.mapper.RoleMapper;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.UserRoleMapper;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.impl.OrderSubmittedConsumeService;
import com.workorder.service.impl.OrderSubmittedConsumeService.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P5 步骤 2 的核心验证：**提交通知异步化 + 两道幂等防线 + 状态守卫**（真库）。
 *
 * <p>四条必须成立的事：
 * <ol>
 *   <li><b>接收人解析不在提交事务里</b>：提交只写一行 outbox（`ORDER_SUBMITTED`），此刻站内信**一条都没有**；</li>
 *   <li><b>消费端按角色群发</b>：HANDLER 角色下每个处理人各得一条，且 `ref_type/ref_id` 已回填；</li>
 *   <li><b>两道防线各自独立可判</b>：重复消费被去重表挡住（DUPLICATE）；即便绕过去重表，
 *       同一条事件对同一接收人也会被 `UNIQUE(event_id, user_id)` 挡住；</li>
 *   <li><b>状态守卫</b>：工单已不在 PENDING 池 → SKIPPED（不是失败，不写重试账本）。</li>
 * </ol>
 *
 * <p>与 `OrderTriageConsumeTest` 一样用"水位线 + 事后清理"而不是 `@Transactional`：
 * 消费事务与重试账本的 REQUIRES_NEW 都必须真实提交才测得到。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderSubmittedNotifyTest {

    @Autowired private OrderSubmittedConsumeService orderSubmittedConsumeService;
    @Autowired private WorkOrderService workOrderService;
    @Autowired private NotificationService notificationService;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private WorkOrderLogMapper workOrderLogMapper;
    @Autowired private EventOutboxMapper eventOutboxMapper;
    @Autowired private ConsumeRecordMapper consumeRecordMapper;
    @Autowired private MessageRetryMapper messageRetryMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private UserRoleMapper userRoleMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    private final List<Long> handlerIds = new ArrayList<>();

    private Long orderWatermark;
    private Long logWatermark;
    private Long outboxWatermark;
    private Long consumeWatermark;
    private Long retryWatermark;
    private Long notificationWatermark;

    @BeforeEach
    void setUp() {
        orderWatermark = maxOrderId();
        logWatermark = maxLogId();
        outboxWatermark = maxOutboxId();
        consumeWatermark = maxConsumeId();
        retryWatermark = maxRetryId();
        notificationWatermark = maxNotificationId();

        // 两个处理人：证明"按角色群发"而不是只发给某一个用户
        handlerIds.clear();
        handlerIds.add(createHandler());
        handlerIds.add(createHandler());
    }

    @AfterEach
    void cleanUp() {
        notificationMapper.delete(new LambdaQueryWrapper<Notification>().gt(Notification::getId, notificationWatermark));
        messageRetryMapper.delete(new LambdaQueryWrapper<MessageRetry>().gt(MessageRetry::getId, retryWatermark));
        consumeRecordMapper.delete(new LambdaQueryWrapper<ConsumeRecord>().gt(ConsumeRecord::getId, consumeWatermark));
        eventOutboxMapper.delete(new LambdaQueryWrapper<EventOutbox>().gt(EventOutbox::getId, outboxWatermark));
        workOrderLogMapper.delete(new LambdaQueryWrapper<WorkOrderLog>().gt(WorkOrderLog::getId, logWatermark));
        workOrderMapper.delete(new LambdaQueryWrapper<WorkOrder>().gt(WorkOrder::getId, orderWatermark));
        for (Long uid : handlerIds) {
            userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, uid));
            userMapper.deleteById(uid);
        }
        handlerIds.clear();
    }

    @Test
    @DisplayName("【核心】提交只写一行 outbox：此刻站内信 0 条（接收人解析没有跑在提交事务里）")
    void submit_writesOutboxOnly_noNotificationYet() {
        WorkOrder order = workOrderService.submitOrder(req("NETWORK", 0), 1L);
        String eventId = "order:" + order.getId() + ":v0:ORDER_SUBMITTED";

        EventOutbox row = outboxRow(order.getId(), "ORDER_SUBMITTED");
        assertNotNull(row, "提交必须在同一事务内写 ORDER_SUBMITTED 事件（否则通知永远不会发生）");
        assertEquals(eventId, row.getEventId(), "提交通知的事件键 = order:{id}:v0:ORDER_SUBMITTED");
        assertEquals("PENDING", row.getStatus(), "outbox 记录先落 PENDING，由投递任务发出去");

        assertEquals(0, countNotificationsByEvent(eventId),
                "提交事务里不得写站内信——它只写 outbox（plan §2.1：接收人解析必须异步）");
        assertEquals(0, newNotifications().size(), "提交路径整条不得产生任何站内信");
    }

    @Test
    @DisplayName("消费端按角色群发：HANDLER 下每个处理人各一条，event_id / ref_type / ref_id 都写对")
    void consume_notifiesEveryHandlerOnce() {
        Long orderId = insertOrder("PENDING");
        String eventId = "order:" + orderId + ":v0:ORDER_SUBMITTED";

        Outcome outcome = orderSubmittedConsumeService.consume(eventId, orderId, payload(orderId));

        assertEquals(Outcome.NOTIFIED, outcome);
        for (Long uid : handlerIds) {
            List<Notification> mine = notificationsOfUserForEvent(uid, eventId);
            assertEquals(1, mine.size(), "每个处理人各收到一条（userId=" + uid + "）");
            Notification n = mine.get(0);
            assertEquals("ORDER", n.getRefType(), "ref_type 必须回填（第二道防线之外，前端也要用它跳转）");
            assertEquals(orderId, n.getRefId());
            assertTrue(n.getTitle().contains("WO-") || n.getTitle().contains("新工单"),
                    "标题应说明'池子里有新单'：" + n.getTitle());
            assertEquals(0, n.getIsRead());
        }
        assertEquals(1, countConsume(eventId), "去重记录已落库");
        assertNull(findRetry(eventId), "成功不写重试账本");
    }

    @Test
    @DisplayName("重复投递：第二次 DUPLICATE，站内信不增加（第一道防线：去重表）")
    void duplicateDelivery_secondIsDuplicate_noExtraNotification() {
        Long orderId = insertOrder("PENDING");
        String eventId = "order:" + orderId + ":v0:ORDER_SUBMITTED";

        assertEquals(Outcome.NOTIFIED, orderSubmittedConsumeService.consume(eventId, orderId, payload(orderId)));
        assertEquals(Outcome.DUPLICATE, orderSubmittedConsumeService.consume(eventId, orderId, payload(orderId)));

        assertEquals(handlerIds.size(), countNotificationsByEvent(eventId),
                "重复投递不得给同一个接收人发第二条");
        assertEquals(1, countConsume(eventId));
    }

    @Test
    @DisplayName("【第二道防线】绕过去重表直接群发两次：UNIQUE(event_id,user_id) 让第二次新增 0 条")
    void secondDefense_sameEventId_sendsNothingExtra() {
        String eventId = "order:999999:v0:ORDER_SUBMITTED";

        int first = notificationService.sendToRoleOnce("HANDLER", "t", "c", eventId, "ORDER", 999999L);
        int second = notificationService.sendToRoleOnce("HANDLER", "t", "c", eventId, "ORDER", 999999L);

        assertEquals(handlerIds.size(), first, "第一次：HANDLER 下每个处理人一条");
        assertEquals(0, second, "第二次：同一条事件对同一接收人不得再插一条（唯一索引挡住）");
        assertEquals(handlerIds.size(), countNotificationsByEvent(eventId));
    }

    @Test
    @DisplayName("状态守卫：工单已不在待分配池 → SKIPPED（不是失败，不写重试账本、不通知）")
    void orderNotPending_skipped_noNotification() {
        Long orderId = insertOrder("ACCEPTED");
        String eventId = "order:" + orderId + ":v0:ORDER_SUBMITTED";

        Outcome outcome = orderSubmittedConsumeService.consume(eventId, orderId, payload(orderId));

        assertEquals(Outcome.SKIPPED, outcome);
        assertEquals(0, countNotificationsByEvent(eventId), "已被人抢走的单不该再广播'池子里有新单'");
        assertEquals(1, countConsume(eventId), "SKIPPED 是正常结论，去重记录照留");
        assertNull(findRetry(eventId), "SKIPPED **不是失败**：不得写重试账本（否则会反复重投一张已接的单）");
    }

    // ────────────── helpers ──────────────

    private SubmitOrderReq req(String type, Integer priority) {
        SubmitOrderReq r = new SubmitOrderReq();
        r.setTitle("提交通知测试");
        r.setContent("x");
        r.setType(type);
        r.setPriority(priority);
        return r;
    }

    private String payload(Long orderId) {
        return "{\"orderId\":" + orderId + "}";
    }

    /** 直接落一张 PENDING 工单（不走 submitOrder：本类测的是消费端，提交路径由另一个用例单独钉住） */
    private Long insertOrder(String status) {
        WorkOrder[] holder = new WorkOrder[1];
        transactionTemplate.execute(tx -> {
            WorkOrder o = new WorkOrder();
            o.setOrderNo("TST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            o.setTitle("提交通知测试");
            o.setContent("x");
            o.setType("NETWORK");
            o.setPriority(0);
            o.setStatus(status);
            o.setSubmitterId(1L);
            o.setRejectCount(0);
            o.setMaxReject(3);
            o.setTriageStatus("DONE");
            o.setSlaDeadline(LocalDateTime.now().plusMinutes(480));
            o.setVersion(0);
            o.setCreatedAt(LocalDateTime.now());
            o.setUpdatedAt(LocalDateTime.now());
            workOrderMapper.insert(o);
            holder[0] = o;
            return null;
        });
        return holder[0].getId();
    }

    private Long createHandler() {
        User user = new User();
        user.setUsername("notify-test-handler-" + System.nanoTime());
        user.setPassword("$2a$encoded");
        user.setStatus(1);
        userMapper.insert(user);
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, "HANDLER"));
        assertNotNull(role, "测试库必须有 HANDLER 角色（种子数据）");
        UserRole ur = new UserRole();
        ur.setUserId(user.getId());
        ur.setRoleId(role.getId());
        userRoleMapper.insert(ur);
        return user.getId();
    }

    private EventOutbox outboxRow(Long orderId, String eventType) {
        return eventOutboxMapper.selectOne(new LambdaQueryWrapper<EventOutbox>()
                .eq(EventOutbox::getAggregateId, orderId)
                .eq(EventOutbox::getEventType, eventType)
                .last("LIMIT 1"));
    }

    private long countConsume(String eventId) {
        return consumeRecordMapper.selectCount(new LambdaQueryWrapper<ConsumeRecord>()
                .eq(ConsumeRecord::getEventId, eventId));
    }

    private MessageRetry findRetry(String eventId) {
        return messageRetryMapper.selectOne(new LambdaQueryWrapper<MessageRetry>()
                .eq(MessageRetry::getEventId, eventId));
    }

    private List<Notification> newNotifications() {
        return notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .gt(Notification::getId, notificationWatermark));
    }

    private long countNotificationsByEvent(String eventId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getEventId, eventId));
    }

    private List<Notification> notificationsOfUserForEvent(Long userId, String eventId) {
        return notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getEventId, eventId));
    }

    private Long maxOrderId() {
        WorkOrder o = workOrderMapper.selectOne(new LambdaQueryWrapper<WorkOrder>()
                .select(WorkOrder::getId).orderByDesc(WorkOrder::getId).last("LIMIT 1"));
        return o == null || o.getId() == null ? 0L : o.getId();
    }

    private Long maxLogId() {
        WorkOrderLog l = workOrderLogMapper.selectOne(new LambdaQueryWrapper<WorkOrderLog>()
                .select(WorkOrderLog::getId).orderByDesc(WorkOrderLog::getId).last("LIMIT 1"));
        return l == null || l.getId() == null ? 0L : l.getId();
    }

    private Long maxOutboxId() {
        EventOutbox r = eventOutboxMapper.selectOne(new LambdaQueryWrapper<EventOutbox>()
                .select(EventOutbox::getId).orderByDesc(EventOutbox::getId).last("LIMIT 1"));
        return r == null || r.getId() == null ? 0L : r.getId();
    }

    private Long maxConsumeId() {
        ConsumeRecord r = consumeRecordMapper.selectOne(new LambdaQueryWrapper<ConsumeRecord>()
                .select(ConsumeRecord::getId).orderByDesc(ConsumeRecord::getId).last("LIMIT 1"));
        return r == null || r.getId() == null ? 0L : r.getId();
    }

    private Long maxRetryId() {
        MessageRetry r = messageRetryMapper.selectOne(new LambdaQueryWrapper<MessageRetry>()
                .select(MessageRetry::getId).orderByDesc(MessageRetry::getId).last("LIMIT 1"));
        return r == null || r.getId() == null ? 0L : r.getId();
    }

    private Long maxNotificationId() {
        Notification n = notificationMapper.selectOne(new LambdaQueryWrapper<Notification>()
                .select(Notification::getId).orderByDesc(Notification::getId).last("LIMIT 1"));
        return n == null || n.getId() == null ? 0L : n.getId();
    }
}
