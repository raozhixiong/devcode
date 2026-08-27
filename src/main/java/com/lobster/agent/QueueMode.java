package com.lobster.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 队列模式（对齐 05 设计 §2.7 / FR-B1-2）：busy 时新输入的分流策略。
 * - steer：注入活跃 run（工具边界生效，M3 简化为收件箱高优先级，轮内 tool 边界读出）
 * - followup：入队下一轮（现有收件箱行为）
 * - collect：500ms 窗口合并为一条
 * - interrupt：中止当前 run -> 最新消息入队
 */
public class QueueMode {

    public enum Mode {
        STEER, FOLLOWUP, COLLECT, INTERRUPT;

        public static Mode of(String s) {
            if (s == null) return STEER;
            return switch (s.toLowerCase()) {
                case "followup" -> FOLLOWUP;
                case "collect" -> COLLECT;
                case "interrupt" -> INTERRUPT;
                default -> STEER;
            };
        }
    }

    private final Map<String, Mode> sessionModes = new ConcurrentHashMap<>();

    public Mode mode(String sessionId) {
        return sessionModes.getOrDefault(sessionId, Mode.STEER);
    }

    public void setMode(String sessionId, Mode mode) {
        if (mode == null || mode == Mode.STEER) sessionModes.remove(sessionId);
        else sessionModes.put(sessionId, mode);
    }

    /** collect 模式的合并窗口（毫秒）。 */
    public static final long COLLECT_WINDOW_MS = 500;

    /** busy 输入的处置结果。 */
    public record Disposition(Mode mode, boolean queued, boolean interrupted, String note) {}

    /**
     * busy 时输入处置：
     * - STEER/FOLLOWUP/COLLECT -> 入收件箱（steer 标记插队优先；collect 提示窗口合并）
     * - INTERRUPT -> 请求中止当前 run + 最新消息入队
     */
    public Disposition dispatch(String sessionId, boolean busy,
                                java.util.function.Consumer<String> enqueue,
                                Runnable requestAbort) {
        Mode m = mode(sessionId);
        if (!busy) {
            return new Disposition(m, false, false, "idle 直接执行");
        }
        return switch (m) {
            case STEER -> {
                enqueue.accept("");
                yield new Disposition(m, true, false, "已插入活跃 run（steer）");
            }
            case FOLLOWUP -> {
                enqueue.accept("");
                yield new Disposition(m, true, false, "已排队下一轮（followup）");
            }
            case COLLECT -> {
                enqueue.accept("");
                yield new Disposition(m, true, false, "已入合并窗口（collect " + COLLECT_WINDOW_MS + "ms）");
            }
            case INTERRUPT -> {
                requestAbort.run();
                enqueue.accept("");
                yield new Disposition(m, true, true, "已请求中止当前 run，最新消息将接管（interrupt）");
            }
        };
    }
}
