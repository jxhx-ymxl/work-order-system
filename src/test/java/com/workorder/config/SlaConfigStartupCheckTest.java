package com.workorder.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.workorder.entity.SlaConfig;
import com.workorder.mapper.SlaConfigMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 启动自检 {@link SlaConfigStartupCheck}（P0a 的 B3）。
 *
 * <p>用 mock mapper + Logback ListAppender 捕获日志，断言"缺配置时必须 error 且指出缺哪个组合"。
 * 这样不必依赖真实配置表的状态——因为 P0b 前后配置表的合法集合不同，真实库断言会随时间失效。</p>
 */
@ExtendWith(MockitoExtension.class)
class SlaConfigStartupCheckTest {

    @Mock
    private SlaConfigMapper slaConfigMapper;

    @InjectMocks
    private SlaConfigStartupCheck check;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger checkerLogger;

    @BeforeEach
    void setUp() {
        checkerLogger = (Logger) LoggerFactory.getLogger(SlaConfigStartupCheck.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        checkerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        checkerLogger.detachAppender(logAppender);
    }

    private SlaConfig config(String type, int priority) {
        SlaConfig c = new SlaConfig();
        c.setType(type);
        c.setPriority(priority);
        c.setAcceptMinutes(30);
        c.setFinishMinutes(120);
        return c;
    }

    private List<SlaConfig> completeConfig() {
        List<SlaConfig> list = new ArrayList<>();
        for (String type : List.of("NETWORK", "UTILITY", "DORM", "OTHER")) {
            for (int priority : List.of(0, 1)) {
                list.add(config(type, priority));
            }
        }
        return list;
    }

    @Test
    @DisplayName("B3：配置完整时记 info，不记 error")
    void complete_logsInfo() {
        when(slaConfigMapper.selectList(isNull())).thenReturn(completeConfig());

        check.ensureSlaConfigComplete();

        assertTrue(logAppender.list.stream().anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("覆盖完整")),
                "配置完整时应记 info");
        assertTrue(logAppender.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR),
                "配置完整时不应有 error");
    }

    @Test
    @DisplayName("B3：缺配置时记 error，并明确指出缺哪个组合")
    void missingCombos_logsErrorWithDetail() {
        // 只给 OTHER/0 与 OTHER/1，其余 6 个组合全缺
        when(slaConfigMapper.selectList(isNull()))
                .thenReturn(List.of(config("OTHER", 0), config("OTHER", 1)));

        check.ensureSlaConfigComplete();

        ILoggingEvent error = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .findFirst()
                .orElse(null);
        assertNotNull(error, "缺配置时必须记 error");
        String msg = error.getFormattedMessage();
        assertTrue(msg.contains("缺 6 个组合") || msg.contains("缺失 6 个组合"),
                "error 应给出缺失数量，实际：" + msg);
        assertTrue(msg.contains("NETWORK//0"), "error 应指出缺失的具体组合，实际：" + msg);
        assertTrue(msg.contains("DORM//1"), "error 应指出缺失的具体组合，实际：" + msg);
    }

    @Test
    @DisplayName("B3：空表时记 error（不抛异常、不阻止启动）")
    void emptyTable_logsErrorAndDoesNotThrow() {
        when(slaConfigMapper.selectList(isNull())).thenReturn(List.of());

        assertDoesNotThrow(() -> check.ensureSlaConfigComplete(),
                "自检不得阻止启动：只记录，不抛异常");
        assertTrue(logAppender.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("缺失 8 个组合")),
                "空表应报缺 8 个组合");
    }
}
