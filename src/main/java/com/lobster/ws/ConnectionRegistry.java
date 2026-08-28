package com.lobster.ws;

import com.lobster.auth.AuthInfo;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 连接级共享状态：鉴权态、会话表、退订句柄。供 WsHandler 与 auth/channel 处理器共享。 */
@Component
public class ConnectionRegistry {

    private final Map<String, AuthInfo> authBySession = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Runnable> unsubscribes = new ConcurrentHashMap<>();

    public void putSession(String id, WebSocketSession s) { sessions.put(id, s); }
    public void removeSession(String id) { sessions.remove(id); authBySession.remove(id); }
    public Map<String, WebSocketSession> sessions() { return sessions; }

    public void putAuth(String id, AuthInfo info) { authBySession.put(id, info); }
    public AuthInfo getAuth(String id) { return authBySession.get(id); }
    public boolean isAuthed(String id) { return authBySession.containsKey(id); }

    public void putUnsub(String id, Runnable unsub) { unsubscribes.put(id, unsub); }
    public Runnable takeUnsub(String id) { return unsubscribes.remove(id); }

    public void revokeAuth(String id) { authBySession.remove(id); }
}
