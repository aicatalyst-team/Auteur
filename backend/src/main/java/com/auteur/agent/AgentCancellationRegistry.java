package com.auteur.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按 sessionId 维护一个取消信号(AtomicBoolean)。
 *
 * 设计:同一 session 不可能并发跑多个 turn(前端 busy 锁防住了),所以一对一映射够用。
 * 即使竞态导致老 signal 被覆盖,老 turn 持有的局部 signal 引用仍指向自己那个 AtomicBoolean,
 * cancel 也只会影响"现役"那一个,不会误伤别的。
 *
 * 触发点:
 *   - AgentController.chat 的 emitter.onTimeout/onError 调 cancel(sessionId)
 *   - 端点 POST /agent/sessions/{id}/cancel(前端切会话/卸载时调)
 *
 * 检查点(由 AgentLoopService.turn 主动 poll):
 *   - 每轮 LLM 调用之前
 *   - 每个 tool 执行之前
 *   - 审批等待结束之后
 *
 * 联动:cancel(sessionId) 同时调 ApprovalGate.cancelSession(sessionId),
 * 让阻塞在审批 future.get 的线程立即解除等待,而不必等到 60s 自然超时。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentCancellationRegistry {

    private final ApprovalGate approvalGate;

    private final ConcurrentHashMap<Long, AtomicBoolean> signals = new ConcurrentHashMap<>();

    /** turn 开始时调,返回自己持有的 signal 引用。 */
    public AtomicBoolean register(Long sessionId) {
        AtomicBoolean signal = new AtomicBoolean(false);
        signals.put(sessionId, signal);
        return signal;
    }

    /**
     * turn 结束时调。conditional remove:只移除我注册的那个,不误删后来者。
     * 防止新 turn 已经 register 后,老 turn 的 finally 把新 signal 抹掉。
     */
    public void unregister(Long sessionId, AtomicBoolean ownSignal) {
        signals.remove(sessionId, ownSignal);
    }

    /** 外部触发取消(SSE 错误/超时/前端显式调 cancel 端点)。 */
    public boolean cancel(Long sessionId) {
        AtomicBoolean s = signals.get(sessionId);
        if (s == null) {
            log.debug("[Agent] cancel 找不到 sessionId={} (可能已结束)", sessionId);
            // 即使没有活跃 turn,也尝试清掉残留的审批 future(极端 race 兜底)。
            approvalGate.cancelSession(sessionId);
            return false;
        }
        boolean fresh = s.compareAndSet(false, true);
        if (fresh) {
            log.info("[Agent] cancel sessionId={}", sessionId);
            // 同步唤醒挂起的审批等待,turn 不必等 500ms 轮询或 60s 超时。
            approvalGate.cancelSession(sessionId);
        }
        return fresh;
    }
}
