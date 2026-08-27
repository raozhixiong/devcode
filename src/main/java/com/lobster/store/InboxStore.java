package com.lobster.store;

import com.lobster.util.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** 输入收件箱：busy 时消息入队，轮结束 admit（合并同源为新 user 消息）。 */
public class InboxStore {

    private final JdbcTemplate jdbc;

    public InboxStore(AgentDb db) {
        this.jdbc = db.jdbc();
    }

    /** 入队待处理输入，返回 admitted_seq。 */
    public long enqueue(String sessionId, String prompt) {
        long seq = nextSeq(sessionId);
        jdbc.update("INSERT INTO session_input(id, session_id, prompt, delivery, admitted_seq, created_at) "
                        + "VALUES(?,?,?,?,?,?)",
                Ulid.next("inp_"), sessionId, prompt, "queued", seq, System.currentTimeMillis());
        return seq;
    }

    /** 取出并删除全部排队输入（FIFO）。 */
    public List<String> drain(String sessionId) {
        List<String> prompts = jdbc.query(
                "SELECT prompt FROM session_input WHERE session_id=? ORDER BY admitted_seq, id",
                (rs, i) -> rs.getString(1), sessionId);
        if (!prompts.isEmpty()) {
            jdbc.update("DELETE FROM session_input WHERE session_id=?", sessionId);
        }
        return prompts;
    }

    public int pendingCount(String sessionId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_input WHERE session_id=?", Integer.class, sessionId);
        return n == null ? 0 : n;
    }

    private long nextSeq(String sessionId) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(admitted_seq), 0) FROM session_input WHERE session_id=?",
                Long.class, sessionId);
        return (max == null ? 0 : max) + 1;
    }
}
