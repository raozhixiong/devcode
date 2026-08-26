package com.lobster.llm;

import java.util.List;
import java.util.stream.Stream;

/** 脚本化 Mock：按序回放事件（测试与无 key 演示）。 */
public class MockLlmProvider implements LlmProvider {

    private final List<LlmEvent> script;

    public MockLlmProvider(List<LlmEvent> script) {
        this.script = List.copyOf(script);
    }

    @Override
    public Stream<LlmEvent> stream(LlmRequest req) {
        return script.stream();
    }
}
