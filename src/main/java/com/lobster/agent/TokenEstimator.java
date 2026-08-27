package com.lobster.agent;

import java.util.List;

import com.lobster.model.Message;
import com.lobster.model.Part;

/** 粗 token 估算（1 token ≈ 4 字符，CJK 按 1 字 ≈ 1 token 加权）。 */
public final class TokenEstimator {

    private TokenEstimator() {}

    public static long estimate(String s) {
        if (s == null || s.isEmpty()) return 0;
        long ascii = 0, cjk = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x2E80) ascii++; else cjk++;
        }
        return ascii / 4 + cjk;
    }

    public static long estimate(Message m) {
        long total = 4; // role 开销
        for (Part p : m.parts()) {
            if (p instanceof Part.Text t) total += estimate(t.text());
            else if (p instanceof Part.Reasoning r) total += estimate(r.text());
            else if (p instanceof Part.Tool tool) {
                if (tool.state() instanceof Part.ToolState.Completed c) {
                    total += estimate(c.output()) / 4; // 历史中工具输出权重降低
                } else if (tool.state() instanceof Part.ToolState.Error e) {
                    total += estimate(e.error());
                }
            } else if (p instanceof Part.Compaction c) {
                total += estimate(c.summary());
            }
        }
        return total;
    }

    public static long estimate(List<Message> history) {
        long total = 0;
        for (Message m : history) total += estimate(m);
        return total;
    }
}
