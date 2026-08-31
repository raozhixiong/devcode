package com.lobster.workboard;

import com.lobster.event.Events;
import com.lobster.event.EventBus;
import com.lobster.event.LobsterEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话生命周期联动：订阅会话状态/空闲事件，把 worker 会话的结束同步回看板卡片
 * （dispatch 线程结束也会调用，这里作为事件驱动兜底路径）。
 */
public class LifecycleSyncService {

    private static final Logger log = LoggerFactory.getLogger(LifecycleSyncService.class);

    public LifecycleSyncService(EventBus bus, DispatchService dispatch) {
        bus.subscribeAll((LobsterEvent ev) -> {
            try {
                if (!Events.SESSION_IDLE.equals(ev.type()) && !Events.SESSION_STATUS.equals(ev.type())) return;
                String sessionKey = ev.aggregateId();
                if (sessionKey == null) return;
                dispatch.onWorkerEnded(sessionKey);
            } catch (RuntimeException ignored) {
                log.debug("lifecycle sync 跳过事件 type={}", ev.type());
            }
        });
        log.info("workboard lifecycle-sync 已订阅会话事件");
    }
}
