/* Lobster M6 工作台客户端：三栏 + 命令面板 + 类型感知工具卡片 */
(function () {
  const $ = (id) => document.getElementById(id);
  const messages = $("messages");
  const input = $("input");
  const sendBtn = $("send");
  const conn = $("conn"), connText = $("connText");

  let ws = null, busy = false, streamingText = null;
  let currentSession = "main";
  let commands = [], skills = [], references = [];
  let paletteItems = [], paletteSel = 0, paletteMode = null; // mode: 'cmd' | 'slash' | 'at'

  function setConnected(on) {
    conn.className = "dot " + (on ? "on" : "off");
    connText.textContent = on ? "已连接" : "未连接";
  }
  function connect() {
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    ws = new WebSocket(proto + "//" + location.host + "/ws");
    ws.onopen = () => { setConnected(true); bootstrap(); };
    ws.onclose = () => { setConnected(false); setTimeout(connect, 2000); };
    ws.onmessage = (e) => handleFrame(JSON.parse(e.data));
  }
  let seq = 0;
  const nextId = () => "c-" + (++seq);
  function send(method, params) {
    ws.send(JSON.stringify({ type: "req", id: nextId(), method, params }));
  }
  function rpc(method, params) {
    return new Promise((resolve) => {
      const id = nextId();
      const h = (e) => {
        const f = JSON.parse(e.data);
        if (f.type === "res" && f.id === id) { ws.removeEventListener("message", h); resolve(f); }
      };
      ws.addEventListener("message", h);
      ws.send(JSON.stringify({ type: "req", id, method, params }));
    });
  }

  async function bootstrap() {
    try {
      const c = await rpc("command.list", {});
      commands = (c.payload && c.payload.commands) || [];
    } catch (e) {}
    try {
      const s = await rpc("skills.list", {});
      skills = (s.payload && s.payload.skills) || [];
    } catch (e) {}
    try {
      const r = await rpc("reference.list", {});
      references = (r.payload && r.payload.references) || [];
    } catch (e) {}
    try {
      const i = await rpc("integration.list", {});
      renderIntegrations((i.payload && i.payload.integrations) || []);
    } catch (e) {}
    try {
      const a = await rpc("artifact.list", { sessionId: currentSession });
      renderArtifacts(a.payload && a.payload.artifacts || []);
    } catch (e) {}
    loadHistory(currentSession);
  }

  function handleFrame(frame) {
    if (frame.type === "res") return handleRes(frame);
    if (frame.type === "event") return handleEvent(frame);
  }
  function handleRes(frame) {
    if (frame.ok && frame.payload && frame.payload.status === "started") setBusy(true);
  }
  function handleEvent(ev) {
    const p = ev.payload || {};
    switch (ev.event) {
      case "session.next.text.delta":
        if (!streamingText) streamingText = bubble("assistant", "");
        streamingText.el.textContent += p.delta; scroll(); break;
      case "session.next.text.ended": streamingText = null; break;
      case "session.next.tool.called":
        streamingText = null; toolCard(p.tool, { running: true }); scroll(); break;
      case "session.next.tool.success": toolCard(p.title || p.tool, { output: p.output }); scroll(); break;
      case "session.next.tool.failed": toolCard(p.tool, { error: p.error }); scroll(); break;
      case "session.idle": case "session.status":
        if (ev.event === "session.status" && p.type !== "idle") break;
        streamingText = null; setBusy(false);
        rpc("artifact.list", { sessionId: currentSession }).then(r => renderArtifacts(r.payload.artifacts || []));
        break;
      case "permission.asked": showPermissionDialog(p.requestId, p.permission, p.patterns || []); break;
      case "reference.changed": rpc("reference.list", {}).then(r => references = r.payload.references || []); break;
    }
  }

  function bubble(cls, text) {
    const el = document.createElement("div");
    el.className = "msg " + cls; el.textContent = text;
    messages.appendChild(el); return { el };
  }
  const toolCards = new Map();
  function toolCard(title, opts) {
    const key = title + ":" + (opts.running ? "run" : opts.output || opts.error || "");
    if (opts.running) {
      const el = document.createElement("div");
      el.className = "msg tool";
      el.innerHTML = '<span class="title"></span> <span class="spinner">⠋</span>';
      el.querySelector(".title").textContent = "🔧 " + title;
      messages.appendChild(el); toolCards.set(title, el); return;
    }
    const el = toolCards.get(title) || document.createElement("div");
    el.className = "msg tool" + (opts.error ? " error" : "");
    const d = document.createElement("details"); d.open = false;
    const sum = document.createElement("summary"); sum.textContent = (opts.error ? "❌ " : "✅ ") + title;
    const pre = document.createElement("pre");
    pre.innerHTML = formatOutput(opts.error || opts.output || "");
    d.appendChild(sum); d.appendChild(pre);
    el.innerHTML = ""; el.appendChild(d);
    if (!toolCards.get(title)) messages.appendChild(el);
    toolCards.delete(title);
  }
  function formatOutput(text) {
    const esc = (s) => s.replace(/[&<>]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]));
    return esc(text).split("\n").map(l => {
      if (l.startsWith("+") && !l.startsWith("+++")) return '<span style="color:#7ee787">'+l+'</span>';
      if (l.startsWith("-") && !l.startsWith("---")) return '<span style="color:#ff7b72">'+l+'</span>';
      if (l.startsWith("@@")) return '<span style="color:#79c0ff">'+l+'</span>';
      return l;
    }).join("\n");
  }

  function setBusy(b) {
    busy = b; sendBtn.disabled = b; input.disabled = b;
    if (!b) input.focus();
  }
  function scroll() { messages.scrollTop = messages.scrollHeight; }

  async function loadHistory(sessionKey) {
    const r = await rpc("chat.history", { sessionKey });
    messages.innerHTML = "";
    (r.payload && r.payload.messages || []).forEach(m => {
      const role = m.role === "user" ? "user" : "assistant";
      const b = bubble(role, m.parts.map(p => p.text || (p.tool ? p.tool : "") || "").join("\n"));
    });
    scroll();
  }

  function submit() {
    const text = input.value.trim();
    if (!text || busy || !ws || ws.readyState !== 1) return;
    if (text.startsWith("/")) { runCommand(text); input.value = ""; return; }
    bubble("user", text); input.value = "";
    send("chat.send", { sessionKey: currentSession, text });
    setBusy(true); scroll();
  }
  function submitText(text) {
    bubble("user", text); send("chat.send", { sessionKey: currentSession, text });
    setBusy(true); scroll();
  }
  function runCommand(slash) {
    switch (slash) {
      case "/clear": messages.innerHTML = ""; sys("已清空会话"); return;
      case "/new": newSession(); return;
      case "/share": shareCurrent(); return;
      default: submitText(slash);
    }
  }

  /* 会话树 */
  function renderSessions() {
    const box = $("sessions"); box.innerHTML = "";
    [currentSession].forEach(k => {
      const d = document.createElement("div");
      d.className = "session" + (k === currentSession ? " active" : "");
      d.textContent = k; d.onclick = () => switchSession(k);
      box.appendChild(d);
    });
  }
  function switchSession(k) { currentSession = k; renderSessions(); loadHistory(k); }
  function newSession() {
    const k = prompt("新会话 key：", "s-" + Date.now());
    if (!k) return; currentSession = k; renderSessions(); loadHistory(k);
  }

  /* 右栏面板 */
  function renderIntegrations(list) {
    const box = $("intList"); box.innerHTML = "";
    list.forEach(it => {
      const d = document.createElement("div"); d.className = "item";
      d.innerHTML = '<span class="name"></span><span class="badge ' + (it.status === "connected" ? "" : "off") + '"></span><div class="meta"></div>';
      d.querySelector(".name").textContent = it.name + " (" + it.kind + ")";
      d.querySelector(".badge").textContent = it.status;
      d.querySelector(".meta").textContent = it.id;
      box.appendChild(d);
    });
  }
  function renderArtifacts(list) {
    const box = $("artList"); box.innerHTML = "";
    (list || []).forEach(a => {
      const d = document.createElement("div"); d.className = "item";
      d.innerHTML = '<span class="name"></span><div class="meta"></div>';
      d.querySelector(".name").textContent = a.name + " · " + a.kind;
      d.querySelector(".meta").textContent = a.path || a.mime || "";
      box.appendChild(d);
    });
  }
  function renderReferences(list) {
    const box = $("refList"); box.innerHTML = "";
    (list || []).forEach(r => {
      const d = document.createElement("div"); d.className = "item";
      d.innerHTML = '<span class="name"></span><div class="meta"></div>';
      d.querySelector(".name").textContent = r.name + " (" + r.kind + ")";
      d.querySelector(".meta").textContent = r.uri;
      box.appendChild(d);
    });
  }

  /* 审批中心 */
  async function renderApprovals() {
    const box = $("approvalList"); box.innerHTML = "";
    const r = await rpc("approval.list", {});
    const list = (r.payload && r.payload.approvals) || [];
    list.forEach(a => {
      const d = document.createElement("div"); d.className = "item";
      d.innerHTML = '<span class="name"></span><div class="meta"></div><div class="perm-btns" style="margin-top:6px"></div>';
      d.querySelector(".name").textContent = a.kind + " · " + (a.status || "");
      d.querySelector(".meta").textContent = (a.requester || "") + " — " + JSON.stringify(a.payload || {}).slice(0, 80);
      const btns = d.querySelector(".perm-btns");
      if (a.status === "pending") {
        const ok = document.createElement("button"); ok.textContent = "通过";
        ok.onclick = async () => { await rpc("approval.resolve", { id: a.id, approved: true }); renderApprovals(); };
        const no = document.createElement("button"); no.textContent = "拒绝"; no.className = "deny";
        no.onclick = async () => { await rpc("approval.resolve", { id: a.id, approved: false, reason: "rejected" }); renderApprovals(); };
        btns.appendChild(ok); btns.appendChild(no);
      }
      box.appendChild(d);
    });
  }

  /* 审计台账 */
  async function renderAudit() {
    const box = $("auditList"); box.innerHTML = "";
    const r = await rpc("audit.activity.list", { limit: 80 });
    const list = (r.payload && r.payload.events) || (r.payload && r.payload.activities) || [];
    (list || []).forEach(e => {
      const d = document.createElement("div"); d.className = "item";
      d.innerHTML = '<span class="name"></span><div class="meta"></div>';
      d.querySelector(".name").textContent = (e.kind || e.action || "") + " · " + (e.result || "");
      d.querySelector(".meta").textContent = new Date((e.ts || 0)).toLocaleString() + " · " + (e.actor || "");
      box.appendChild(d);
    });
  }

  /* 配置中心 + 插件 */
  async function renderSettings() {
    const cfgBox = $("cfgList"); cfgBox.innerHTML = "";
    const cr = await rpc("config.list", {});
    (cr.payload && cr.payload.entries || []).forEach(en => {
      const d = document.createElement("div"); d.className = "item";
      d.innerHTML = '<span class="name"></span><div class="meta"></div><input class="cfg-val" style="margin-top:6px;width:100%;background:#0c0f14;color:#e6e6e6;border:1px solid #2a3140;border-radius:6px;padding:6px" />';
      d.querySelector(".name").textContent = en.path;
      d.querySelector(".meta").textContent = (en.description || "") + " [" + (en.reloadKind || "") + "]";
      const inp = d.querySelector(".cfg-val"); inp.value = en.value != null ? en.value : "";
      inp.onchange = () => rpc("config.set", { path: en.path, value: inp.value });
      cfgBox.appendChild(d);
    });
    const plBox = $("pluginList"); plBox.innerHTML = "";
    const pr = await rpc("plugins.list", {});
    (pr.payload && pr.payload.plugins || []).forEach(p => {
      const d = document.createElement("div"); d.className = "item";
      d.innerHTML = '<span class="name"></span><span class="badge ' + (p.enabled ? "" : "off") + '"></span><div class="meta"></div>';
      d.querySelector(".name").textContent = p.name + " v" + (p.version || "");
      d.querySelector(".badge").textContent = p.enabled ? "启用" : "停用";
      d.querySelector(".meta").textContent = (p.source || "") + " · " + (p.description || "");
      d.onclick = () => rpc("plugins.setEnabled", { id: p.id, enabled: !p.enabled }).then(renderSettings);
      plBox.appendChild(d);
    });
  }

  /* ⌘K / 斜杠 / @ 面板 */
  function openPalette(mode, items) {
    paletteMode = mode; paletteItems = items; paletteSel = 0;
    $("palette").classList.remove("hidden");
    $("paletteInput").value = mode === "slash" ? "" : (mode === "at" ? "" : "");
    $("paletteInput").focus();
    renderPalette("");
  }
  function renderPalette(q) {
    const box = $("paletteItems"); box.innerHTML = "";
    const items = paletteItems.filter(it => (it.label + " " + (it.cat || "")).toLowerCase().includes(q.toLowerCase()));
    items.forEach((it, i) => {
      const d = document.createElement("div");
      d.className = "palette-item" + (i === paletteSel ? " sel" : "");
      d.innerHTML = '<span></span><span class="cat"></span>';
      d.querySelector("span").textContent = it.label;
      d.querySelector(".cat").textContent = it.cat || "";
      d.onclick = () => choosePalette(it);
      box.appendChild(d);
    });
  }
  function choosePalette(it) {
    $("palette").classList.add("hidden");
    if (paletteMode === "at") { input.value = input.value.replace(/@\S*$/, "@" + it.label + " "); input.focus(); return; }
    if (paletteMode === "slash") { input.value = it.label + " "; input.focus(); return; }
    runCommand(it.label);
  }

  function buildPalette(mode) {
    if (mode === "cmd") {
      const items = commands.map(c => ({ label: c.slashName || ("/" + c.id), cat: c.category, id: c.id }));
      const local = [{ label: "/share", cat: "builtin" }, { label: "/new", cat: "builtin" }, { label: "/clear", cat: "builtin" }];
      openPalette("cmd", items.concat(local));
    } else if (mode === "slash") {
      const items = commands.map(c => ({ label: c.slashName || ("/" + c.id), cat: c.category }));
      openPalette("slash", items.concat([{ label: "/share", cat: "builtin" }, { label: "/new", cat: "builtin" }, { label: "/clear", cat: "builtin" }]));
    } else if (mode === "at") {
      const sItems = skills.map(s => ({ label: s.name, cat: "skill" }));
      const rItems = references.filter(r => r.enabled).map(r => ({ label: r.name, cat: "ref" }));
      openPalette("at", sItems.concat(rItems));
    }
  }

  async function shareCurrent() {
    const r = await rpc("share.create", { sessionKey: currentSession });
    const token = r.payload && r.payload.token;
    if (!token) { sys("分享创建失败"); return; }
    $("shareUrl").textContent = location.origin + "/share/" + token;
    $("shareModal").classList.remove("hidden");
  }

  /* 权限弹窗 */
  function showPermissionDialog(requestId, permission, patterns) {
    document.querySelectorAll(".perm-dialog").forEach(d => d.remove());
    const dlg = document.createElement("div");
    dlg.className = "perm-dialog";
    dlg.innerHTML = '<div class="perm-title">🔐 权限请求: <b></b></div><div class="perm-patterns"></div>' +
      '<div class="perm-btns"><button data-d="ALLOW_ALWAYS">始终允许</button><button data-d="ALLOW_ONCE">仅此一次</button><button data-d="REJECT" class="deny">拒绝</button></div>';
    dlg.querySelector("b").textContent = permission;
    dlg.querySelector(".perm-patterns").textContent = patterns.join("\n");
    dlg.querySelectorAll("button").forEach(b => b.onclick = () => { send("permission.respond", { requestId, decision: b.dataset.d }); dlg.remove(); });
    messages.appendChild(dlg); scroll();
  }
  function sys(text) { const el = document.createElement("div"); el.className = "msg sys"; el.textContent = text; messages.appendChild(el); scroll(); }

  /* 事件绑定 */
  sendBtn.onclick = submit;
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") { submit(); return; }
    if (e.key === "Tab" && (input.value.startsWith("/") || input.value.includes("@"))) { e.preventDefault(); buildPalette(input.value.startsWith("/") ? "slash" : "at"); }
  });
  input.addEventListener("input", () => {
    const v = input.value;
    if (v === "/") buildPalette("slash");
    else if (v.startsWith("@") || /\s@\S*$/.test(v)) buildPalette("at");
  });
  $("paletteBtn").onclick = () => buildPalette("cmd");
  $("paletteInput").addEventListener("input", (e) => renderPalette(e.target.value));
  $("paletteInput").addEventListener("keydown", (e) => {
    const items = $("paletteItems").children;
    if (e.key === "ArrowDown") { paletteSel = Math.min(paletteSel + 1, items.length - 1); renderPalette($("paletteInput").value); }
    else if (e.key === "ArrowUp") { paletteSel = Math.max(paletteSel - 1, 0); renderPalette($("paletteInput").value); }
    else if (e.key === "Enter") { const it = paletteItems.filter(x => (x.label + " " + (x.cat || "")).toLowerCase().includes($("paletteInput").value.toLowerCase()))[paletteSel]; if (it) choosePalette(it); }
    else if (e.key === "Escape") { $("palette").classList.add("hidden"); }
  });
  document.addEventListener("keydown", (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") { e.preventDefault(); buildPalette("cmd"); }
  });
  $("newSession").onclick = newSession;
  document.querySelectorAll(".tab").forEach(t => t.onclick = () => {
    document.querySelectorAll(".tab").forEach(x => x.classList.remove("on"));
    t.classList.add("on");
    document.querySelectorAll(".tabpane").forEach(p => p.classList.add("hidden"));
    $("tab-" + t.dataset.tab).classList.remove("hidden");
    if (t.dataset.tab === "refs") rpc("reference.list", {}).then(r => renderReferences(r.payload.references || []));
    else if (t.dataset.tab === "approvals") renderApprovals();
    else if (t.dataset.tab === "audit") renderAudit();
    else if (t.dataset.tab === "settings") renderSettings();
  });
  document.querySelector('[data-act="approval-refresh"]').onclick = renderApprovals;
  document.querySelector('[data-act="audit-refresh"]').onclick = renderAudit;
  $("shareClose").onclick = () => $("shareModal").classList.add("hidden");
  $("shareCopy").onclick = () => navigator.clipboard.writeText($("shareUrl").textContent);
  document.querySelector('[data-act="ref-add"]').onclick = async () => {
    const name = prompt("参考库名称："); if (!name) return;
    const uri = prompt("URI（local/git/url）：", "https://"); if (!uri) return;
    await rpc("reference.install", { name, kind: "url", uri });
    const r = await rpc("reference.list", {}); renderReferences(r.payload.references || []);
  };
  document.querySelector('[data-act="int-add"]').onclick = async () => {
    const name = prompt("集成名称："); if (!name) return;
    const kind = prompt("类型（oauth/key）：", "key"); if (!kind) return;
    const it = await rpc("integration.connect.key", { id: (await rpc("integration.list", {})).payload.integrations.length, key: "" }).catch(() => null);
    sys("集成连接需后端实现，已记录意图: " + name);
  };

  renderSessions();
  connect();
})();
