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
      case "permission.asked":
        showPermissionDialog(p.requestId, p.permission, p.patterns || []);
        break;
    }
  }

  function showPermissionDialog(requestId, permission, patterns) {
    // 同一时间只保留一个弹窗
    document.querySelectorAll(".perm-dialog").forEach((d) => d.remove());
    const dlg = document.createElement("div");
    dlg.className = "perm-dialog";
    dlg.innerHTML =
      '<div class="perm-title">🔐 权限请求: <b></b></div>' +
      '<div class="perm-patterns"></div>' +
      '<div class="perm-btns">' +
      '<button data-d="ALLOW_ALWAYS">始终允许</button>' +
      '<button data-d="ALLOW_ONCE">仅此一次</button>' +
      '<button data-d="REJECT" class="deny">拒绝</button></div>';
    dlg.querySelector("b").textContent = permission;
    dlg.querySelector(".perm-patterns").textContent = patterns.join("\n");
    dlg.querySelectorAll("button").forEach((btn) => {
      btn.addEventListener("click", () => {
        send("permission.respond", { requestId, decision: btn.dataset.d });
        dlg.remove();
      });
    });
    messages.appendChild(dlg);
    scroll();
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

  // Plan 模式切换
  const planToggle = document.getElementById("planToggle");
  let planOn = false;
  planToggle.addEventListener("click", () => {
    planOn = !planOn;
    send("mode.set", { sessionKey: "main", mode: planOn ? "plan" : "build" });
    planToggle.textContent = "Plan: " + (planOn ? "开" : "关");
    planToggle.classList.toggle("on", planOn);
    sys(planOn ? "已进入 Plan 模式：只读调研与规划，计划写入 plans/*.md 后调用 plan_exit 交接"
               : "已切换回 Build 模式");
  });

  function sys(text) {
    const el = document.createElement("div");
    el.className = "msg sys";
    el.textContent = text;
    messages.appendChild(el);
    scroll();
  }

  connect();
})();
