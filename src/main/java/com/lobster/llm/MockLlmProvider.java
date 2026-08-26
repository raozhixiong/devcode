package com.lobster.llm;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 脚本化 Mock。
 * - 单脚本模式（List&lt;LlmEvent&gt; 构造器）：每轮回放同一脚本。
 * - 按轮模式（ofTurns）：第 N 轮回放 turns[N]，超出轮次重复最后一组。
 */
public class MockLlmProvider implements LlmProvider {

    private final List<List<LlmEvent>> turns;
    private final boolean singleScript;
    private final AtomicInteger turn = new AtomicInteger();

    /** 单脚本：每轮回放同一脚本。 */
    public MockLlmProvider(List<LlmEvent> script) {
        this.turns = List.of(script);
        this.singleScript = true;
    }

    /** 按轮次脚本：turns.get(0) 第一轮…超出轮次重复最后一组。 */
    public static MockLlmProvider ofTurns(List<List<LlmEvent>> turns) {
        MockLlmProvider p = new MockLlmProvider(List.of());
        return new MockLlmProvider(turns, false);
    }

    private MockLlmProvider(List<List<LlmEvent>> turns, boolean singleScript) {
        this.turns = turns;
        this.singleScript = singleScript;
    }

    @Override
    public Stream<LlmEvent> stream(LlmRequest req) {
        if (singleScript) return turns.get(0).stream();
        int t = Math.min(turn.getAndIncrement(), turns.size() - 1);
        return turns.get(t).stream();
    }
}
