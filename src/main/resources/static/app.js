/* Lobster M1 演示客户端：WS 帧协议 + 消息流渲染 */
(function () {
  const messages = document.getElementById("messages");
  const input = document.getElementById("input");
  const sendBtn = document.getElementById("send");
  const conn = document.getElementById("conn");
  const connText = document.getElementById("connText");

  let ws = null;
  let busy = false;
  let streamingText = null; // 当前流式 assistant 气泡

  function setConnected(on) {
    conn.className = "dot " + (on ? "on" : "off");
    connText.textContent = on ? "已连接" : "未连接";
  }

  function connect() {
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    ws = new WebSocket(proto + "//" + location.host + "/ws");
    ws.onopen = () => setConnected(true);
    ws.onclose = () => { setConnected(false); setTimeout(connect, 2000); };
    ws.onmessage = (e) => handleFrame(JSON.parse(e.data));
  }

  function send(method, params) {
    ws.send(JSON.stringify({ type: "req", id: nextId(), method, params }));
  }

  let seq = 0;
  function nextId() { return "c-" + (++seq); }

  function handleFrame(frame) {
    if (frame.type === "res") return handleRes(frame);
    if (frame.type === "event") return handleEvent(frame);
  }

  function handleRes(frame) {
    if (frame.ok && frame.payload && frame.payload.status === "started") {
      setBusy(true);
    }
  }

  function handleEvent(ev) {
    const p = ev.payload || {};
    switch (ev.event) {
      case "session.next.text.delta":
        if (!streamingText) streamingText = bubble("assistant", "");
        streamingText.el.textContent += p.delta;
        scroll();
        break;
      case "session.next.text.ended":
        streamingText = null;
        break;
      case "session.next.tool.called":
        streamingText = null;
        toolCard(p.tool, { running: true });
        scroll();
        break;
      case "session.next.tool.success":
        toolCard(p.title || "tool", { output: p.output });
        scroll();
        break;
      case "session.next.tool.failed":
        toolCard(p.tool, { error: p.error });
        scroll();
        break;
      case "session.idle":
      case "session.status":
        if (ev.event === "session.status" && p.type !== "idle") break;
        streamingText = null;
        setBusy(false);
        break;
    }
  }

  function bubble(cls, text) {
    const el = document.createElement("div");
    el.className = "msg " + cls;
    el.textContent = text;
    messages.appendChild(el);
    return { el };
  }

  const toolCards = new Map();
  function toolCard(title, opts) {
    const key = title + ":" + (opts.running ? "run" : opts.output || opts.error || "");
    if (opts.running) {
      const el = document.createElement("div");
      el.className = "msg tool";
      el.innerHTML = '<span class="title"></span> <span class="spinner">⠋</span>';
      el.querySelector(".title").textContent = "🔧 " + title;
      messages.appendChild(el);
      toolCards.set(title, el);
      return;
    }
    const el = toolCards.get(title);
    if (el) {
      el.innerHTML = '<span class="title"></span><pre></pre>';
      el.querySelector(".title").textContent = "✅ " + title;
      el.querySelector("pre").textContent = opts.output || "";
      toolCards.delete(title);
      return;
    }
    const card = document.createElement("div");
    card.className = "msg tool" + (opts.error ? " error" : "");
    card.innerHTML = '<span class="title"></span><pre></pre>';
    card.querySelector(".title").textContent = (opts.error ? "❌ " : "✅ ") + title;
    card.querySelector("pre").textContent = opts.error || opts.output || "";
    messages.appendChild(card);
  }

  function setBusy(b) {
    busy = b;
    sendBtn.disabled = b;
    input.disabled = b;
    if (!b) input.focus();
  }

  function scroll() { messages.scrollTop = messages.scrollHeight; }

  function submit() {
    const text = input.value.trim();
    if (!text || busy || !ws || ws.readyState !== 1) return;
    bubble("user", text);
    input.value = "";
    send("chat.send", { sessionKey: "main", text });
    setBusy(true);
    scroll();
  }

  sendBtn.addEventListener("click", submit);
  input.addEventListener("keydown", (e) => { if (e.key === "Enter") submit(); });

  connect();
})();
