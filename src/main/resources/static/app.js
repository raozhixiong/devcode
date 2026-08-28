/* Lobster 工作台客户端：完整前端（对话 / 看板 / 任务 / 调度 / 记忆 / 用量 / 频道 / 设备 / 管理） */
(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const h = (tag, attrs, ...kids) => {
    const e = document.createElement(tag);
    if (attrs) for (const k in attrs) {
      if (k === "class") e.className = attrs[k];
      else if (k === "html") e.innerHTML = attrs[k];
      else if (k === "text") e.textContent = attrs[k];
      else if (k.startsWith("on") && typeof attrs[k] === "function") e.addEventListener(k.slice(2), attrs[k]);
      else if (k === "dataset") Object.assign(e.dataset, attrs[k]);
      else if (attrs[k] != null) e.setAttribute(k, attrs[k]);
    }
    kids.flat().forEach(c => { if (c == null) return; e.appendChild(typeof c === "string" ? document.createTextNode(c) : c); });
    return e;
  };
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

  /* ---------- 状态 ---------- */
  let ws = null, busy = false, streamingText = null, authed = false;
  let currentSession = "main";
  let sessions = [{ key: "main", title: "main" }];
  let commands = [], skills = [], references = [];
  let currentAgent = null, agents = [];
  let planMode = "build";
  let paletteItems = [], paletteSel = 0, paletteMode = null;
  let permRequestId = null;
  const toolCards = new Map();

  /* ---------- 连接与鉴权 ---------- */
  function setConnected(on) {
    $("conn").className = "dot " + (on ? "on" : "off");
    $("connText").textContent = on ? (authed ? "已连接" : "已连接(未鉴权)") : "未连接";
  }
  function connect() {
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    ws = new WebSocket(proto + "//" + location.host + "/ws");
    ws.onopen = () => { setConnected(true); rpc("connect", { token: localStorage.getItem("lobster_token") || "" }).catch(() => {}); };
    ws.onclose = () => { setConnected(false); authed = false; setTimeout(connect, 2000); };
    ws.onmessage = (e) => { try { handleFrame(JSON.parse(e.data)); } catch (_) {} };
  }
  let seq = 0;
  const nextId = () => "c-" + (++seq);
  function send(method, params) { if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type: "req", id: nextId(), method, params })); }
  function rpc(method, params) {
    return new Promise((resolve, reject) => {
      if (!ws || ws.readyState !== 1) { reject(new Error("no-ws")); return; }
      const id = nextId();
      const h2 = (e) => {
        let f; try { f = JSON.parse(e.data); } catch (_) { return; }
        if (f.type === "res" && f.id === id) { ws.removeEventListener("message", h2);
          if (f.ok) resolve(f); else reject(f.error || { code: "ERR", message: "rpc failed" }); }
      };
      ws.addEventListener("message", h2);
      ws.send(JSON.stringify({ type: "req", id, method, params: params || {} }));
    });
  }
  function handleFrame(frame) {
    if (frame.type === "res") return handleRes(frame);
    if (frame.type === "event") return handleEvent(frame);
  }
  function handleRes(frame) {
    if (frame.method === "connect") {
      if (frame.ok && frame.payload) {
        const p = frame.payload;
        if (p.authRequired && (!p.auth || !p.auth.userId)) { showAuth(!p.hasUsers); return; }
        authed = true;
        if (p.auth && p.auth.username) { $("meBox").classList.remove("hidden"); $("meBox").textContent = "👤 " + p.auth.username + " (" + p.auth.role + ")"; }
        onReady();
      }
      return;
    }
    if (frame.ok && frame.payload && frame.payload.status === "started") setBusy(true);
  }
  function handleEvent(ev) {
    const p = ev.payload || {};
    switch (ev.event) {
      case "connect.challenge": if (p.authRequired) showAuth(false); break;
      case "session.next.text.delta":
        if (!streamingText) streamingText = bubble("assistant", "");
        streamingText.el.textContent += p.delta; scroll(); break;
      case "session.next.text.ended": streamingText = null; break;
      case "session.next.tool.called":
        streamingText = null; toolCard(p.tool, { running: true }); scroll(); break;
      case "session.next.tool.success": toolCard(p.title || p.tool, { output: p.output }); scroll(); break;
      case "session.next.tool.failed": toolCard(p.tool, { error: p.error }); scroll(); break;
      case "session.idle":
      case "session.status":
        if (ev.event === "session.status" && p.type !== "idle") break;
        streamingText = null; setBusy(false);
        rpc("artifact.list", { sessionId: currentSession }).then(r => renderArtifacts(r.payload.artifacts || [])).catch(() => {});
        break;
      case "permission.asked": showPermissionDialog(p.requestId, p.permission, p.patterns || []); break;
      case "permission.replied": hidePermission(); break;
      case "session.mode.switched": setPlan(p.mode === "plan"); break;
      case "session.queue.mode.set": { const s = $("queueMode"); if (s) s.value = p.mode; } break;
      case "reference.changed": rpc("reference.list", {}).then(r => references = r.payload.references || []).catch(() => {}); refreshIf("refs"); break;
      case "integration.changed": refreshIf("ints"); break;
      case "artifact.changed": refreshIf("arts"); break;
      case "hooks.changed": refreshIf("hooks"); break;
      case "skills.changed": refreshIf("skills"); break;
      case "workboard.changed": if (curView === "board") loadBoard(); break;
      case "tasks.changed": if (curView === "tasks") loadTasks(); break;
      case "cron.changed": if (curView === "cron") loadCron(); break;
      case "approval.requested": case "approval.resolved": refreshIf("approvals"); break;
      case "audit.changed": refreshIf("audit"); break;
      case "config.changed": refreshIf("config"); break;
      case "channel.changed": if (curView === "channels") loadChannels(); break;
      case "device.pair.requested": case "device.pair.resolved": case "device.changed": if (curView === "devices") loadDevices(); break;
      case "auth.user.changed": if (curView === "admin") loadUsers(); break;
    }
  }
  function refreshIf(tab) {
    const t = document.querySelector('#inspectorTabs .tab.on');
    if (t && t.dataset.tab === tab) openInspectorTab(tab, true);
  }

  function onReady() {
    bootstrap();
    renderSessions();
    loadAgents();
    refreshActiveView();
  }

  async function bootstrap() {
    try { const c = await rpc("command.list", {}); commands = (c.payload && c.payload.commands) || []; } catch (e) {}
    try { const s = await rpc("skills.list", {}); skills = (s.payload && s.payload.skills) || []; } catch (e) {}
    try { const r = await rpc("reference.list", {}); references = (r.payload && r.payload.references) || []; } catch (e) {}
    loadHistory(currentSession);
  }

  /* ---------- 工具 ---------- */
  function toast(msg) {
    const t = $("toast"); t.textContent = msg; t.classList.remove("hidden");
    clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.add("hidden"), 2600);
  }
  function bubble(cls, text) {
    const el = h("div", { class: "msg " + cls, text: text || "" });
    $("messages").appendChild(el); return { el };
  }
  function sys(text) { $("messages").appendChild(h("div", { class: "msg sys", text: text })); scroll(); }
  function scroll() { const m = $("messages"); m.scrollTop = m.scrollHeight; }
  function setBusy(b) {
    busy = b; $("send").disabled = b; $("input").disabled = b;
    if (!b) $("input").focus();
  }
  function item(o) {
    const it = h("div", { class: "item" + (o.click ? " click" : "") });
    if (o.name) it.appendChild(h("div", { class: "name", text: o.name }));
    if (o.badge != null) it.appendChild(h("span", { class: "badge" + (o.badgeOff ? " off" : ""), text: o.badge }));
    if (o.meta) it.appendChild(h("div", { class: "meta", text: o.meta }));
    if (o.rows) { const r = h("div", { class: "row" }); o.rows.forEach(b => r.appendChild(b)); it.appendChild(r); }
    if (o.onclick) it.addEventListener("click", o.onclick);
    return it;
  }
  function emptyList(box, text) { box.innerHTML = ""; box.appendChild(h("div", { class: "empty", text: text || "暂无数据" })); }
  function trh(cols) { const tr = h("tr"); cols.forEach(c => tr.appendChild(h("th", { text: c }))); return tr; }
  function openModal(title, body, foot) {
    $("modalTitle").textContent = title;
    const b = $("modalBody"); b.innerHTML = ""; (body.nodes || []).forEach(n => b.appendChild(n));
    const f = $("modalFoot"); f.innerHTML = "";
    (foot || []).forEach(btn => f.appendChild(btn));
    $("modal").classList.remove("hidden");
  }
  function closeModal() { $("modal").classList.add("hidden"); }
  function formRow(label, input) { return h("div", { class: "form-row" }, h("label", { text: label }), input); }
  function field(label, value, ph) {
    const inp = h("input", { class: "input", value: value || "", placeholder: ph || "" });
    return { wrap: formRow(label, inp), inp };
  }

  /* ---------- 视图路由 ---------- */
  let curView = "chat";
  function switchView(v) {
    curView = v;
    document.querySelectorAll(".vnav").forEach(b => b.classList.toggle("on", b.dataset.view === v));
    document.querySelectorAll(".view").forEach(s => s.classList.toggle("hidden", s.id !== "view-" + v));
    refreshActiveView();
  }
  function refreshActiveView() {
    if (!authed) return;
    if (curView === "board") loadBoard();
    else if (curView === "tasks") loadTasks();
    else if (curView === "cron") loadCron();
    else if (curView === "memory") { loadMemRecent(); loadMemCurated(); }
    else if (curView === "usage") { loadUsage("agent"); }
    else if (curView === "channels") loadChannels();
    else if (curView === "devices") loadDevices();
    else if (curView === "admin") { loadUsers(); loadAgentsAdmin(); }
    else if (curView === "chat") { openInspectorTab(document.querySelector("#inspectorTabs .tab.on").dataset.tab, true); }
  }

  /* ---------- 对话：会话树 / 智能体 / plan / 权限 ---------- */
  function renderSessions() {
    const box = $("sessions"); box.innerHTML = "";
    sessions.forEach(s => {
      const d = h("div", { class: "session" + (s.key === currentSession ? " active" : "") });
      d.appendChild(h("span", { class: "s-key", text: s.title || s.key }));
      const acts = h("span", { class: "s-act" });
      const mk = (t, fn) => { const b = h("button", { class: "ghost sm", text: t, title: t }); b.onclick = (e) => { e.stopPropagation(); fn(); }; return b; };
      acts.appendChild(mk("⑂", () => forkSession(s.key)));
      acts.appendChild(mk("↺", () => rewindSession(s.key)));
      acts.appendChild(mk("🗀", () => archiveSession(s.key)));
      d.appendChild(acts);
      d.onclick = () => switchSession(s.key);
      box.appendChild(d);
    });
  }
  function switchSession(k) { currentSession = k; renderSessions(); loadHistory(k); }
  async function newSession() {
    const k = prompt("新会话 key：", "s-" + Date.now());
    if (!k) return;
    sessions.push({ key: k, title: k }); renderSessions(); switchSession(k);
  }
  async function renameSession(k) {
    k = k || currentSession;
    const s = sessions.find(x => x.key === k);
    const t = prompt("重命名会话：", s ? s.title : k);
    if (!t) return;
    try { await rpc("sessions.rename", { sessionKey: k, title: t }); } catch (e) { toast("重命名失败: " + (e.message || e)); return; }
    if (s) s.title = t; renderSessions(); toast("已重命名");
  }
  async function forkSession(k) {
    k = k || currentSession;
    const nk = prompt("分叉后的新 key：", k + "-fork");
    if (!nk) return;
    try { const r = await rpc("sessions.fork", { sessionKey: k, newKey: nk }); const key = (r.payload && r.payload.sessionKey) || nk;
      sessions.push({ key, title: key }); renderSessions(); switchSession(key); } catch (e) { toast("分叉失败: " + (e.message || e)); }
  }
  async function rewindSession(k) {
    k = k || currentSession;
    try { const r = await rpc("chat.history", { sessionKey: k }); const msgs = (r.payload && r.payload.messages) || [];
      const upTo = msgs.length ? msgs[0].id : null;
      await rpc("sessions.rewind", { sessionKey: k, upToMessageId: upTo }); loadHistory(k); toast("已回滚"); }
    catch (e) { toast("回滚失败: " + (e.message || e)); }
  }
  async function archiveSession(k) {
    k = k || currentSession;
    try { await rpc("sessions.archive", { sessionKey: k }); sessions = sessions.filter(x => x.key !== k);
      if (currentSession === k && sessions.length) switchSession(sessions[0].key); else renderSessions(); toast("已归档"); }
    catch (e) { toast("归档失败: " + (e.message || e)); }
  }
  async function setQueueMode(mode) { try { await rpc("queue.mode.set", { sessionKey: currentSession, mode }); } catch (e) {} }

  async function loadAgents() {
    try { const r = await rpc("agents.list", {}); agents = (r.payload && r.payload.agents) || []; } catch (e) { agents = []; }
    const box = $("agents"); box.innerHTML = "";
    agents.forEach(a => {
      const d = h("div", { class: "agent" + (currentAgent === a.id ? " active" : "") },
        h("span", { class: "a-emoji", text: a.emoji || "🤖" }),
        h("span", { text: a.name + (a.role ? " · " + a.role : "") }));
      d.onclick = () => { currentAgent = a.id; loadAgents(); };
      box.appendChild(d);
    });
  }

  function setPlan(on) {
    planMode = on ? "plan" : "build";
    $("planToggle").textContent = "Plan: " + (on ? "开" : "关");
    const f = $("planFlag"); f.textContent = "PLAN: " + (on ? "开" : "关"); f.classList.toggle("on", on);
  }
  async function togglePlan() {
    const m = planMode === "plan" ? "build" : "plan";
    try { await rpc("mode.set", { sessionKey: currentSession, mode: m }); setPlan(m === "plan"); }
    catch (e) { toast("切换失败: " + (e.message || e)); }
  }

  function toolCard(title, opts) {
    const key = title + ":" + (opts.running ? "run" : opts.output || opts.error || "");
    if (opts.running) {
      const el = h("div", { class: "msg tool" }, h("span", { class: "title", text: "🔧 " + title }), h("span", { class: "spinner", text: "⠋" }));
      $("messages").appendChild(el); toolCards.set(title, el); return;
    }
    const el = toolCards.get(title) || h("div", { class: "msg tool" });
    el.className = "msg tool" + (opts.error ? " error" : "");
    const d = h("details"); d.open = false;
    d.appendChild(h("summary", { text: (opts.error ? "❌ " : "✅ ") + title }));
    const pre = h("pre"); pre.innerHTML = formatOutput(opts.error || opts.output || "");
    d.appendChild(pre); el.innerHTML = ""; el.appendChild(d);
    if (!toolCards.get(title)) $("messages").appendChild(el);
    toolCards.delete(title);
  }
  function formatOutput(text) {
    return esc(text).split("\n").map(l => {
      if (l.startsWith("+") && !l.startsWith("+++")) return '<span style="color:#7ee787">' + l + '</span>';
      if (l.startsWith("-") && !l.startsWith("---")) return '<span style="color:#ff7b72">' + l + '</span>';
      if (l.startsWith("@@")) return '<span style="color:#79c0ff">' + l + '</span>';
      return l;
    }).join("\n");
  }

  function showPermissionDialog(requestId, permission, patterns) {
    permRequestId = requestId;
    $("permName").textContent = permission;
    $("permPatterns").textContent = (patterns || []).join("\n");
    $("permModal").classList.remove("hidden");
  }
  function hidePermission() { $("permModal").classList.add("hidden"); permRequestId = null; }
  function respondPermission(decision) {
    if (!permRequestId) return;
    send("permission.respond", { requestId: permRequestId, decision });
  }

  async function loadHistory(sessionKey) {
    try { const r = await rpc("chat.history", { sessionKey }); $("messages").innerHTML = "";
      (r.payload && r.payload.messages || []).forEach(m => {
        const role = m.role === "user" ? "user" : "assistant";
        const text = (m.parts || []).map(p => p.text || (p.type === "tool" ? "🔧 " + (p.tool || "") : "") || "").join("\n");
        bubble(role, text);
      }); scroll();
    } catch (e) {}
  }
  function submit() {
    const text = $("input").value.trim();
    if (!text || busy || !ws || ws.readyState !== 1) return;
    if (text.startsWith("/")) { runCommand(text); $("input").value = ""; return; }
    bubble("user", text); $("input").value = "";
    send("chat.send", { sessionKey: currentSession, text });
    setBusy(true); scroll();
  }
  function runCommand(slash) {
    switch (slash) {
      case "/clear": $("messages").innerHTML = ""; sys("已清空会话"); return;
      case "/new": newSession(); return;
      case "/share": shareCurrent(); return;
      default: bubble("user", slash); send("chat.send", { sessionKey: currentSession, text: slash }); setBusy(true); scroll();
    }
  }

  /* ---------- inspector 右栏 ---------- */
  function openInspectorTab(tab, force) {
    document.querySelectorAll("#inspectorTabs .tab").forEach(x => x.classList.toggle("on", x.dataset.tab === tab));
    document.querySelectorAll(".right .tabpane").forEach(p => p.classList.add("hidden"));
    const pane = $("tab-" + tab); if (pane) pane.classList.remove("hidden");
    if (tab === "refs") loadReferences();
    else if (tab === "ints") loadIntegrations();
    else if (tab === "arts") rpc("artifact.list", { sessionId: currentSession }).then(r => renderArtifacts(r.payload.artifacts || [])).catch(() => emptyList($("artList")));
    else if (tab === "hooks") loadHooks();
    else if (tab === "skills") loadSkills();
    else if (tab === "plugins") loadPlugins();
    else if (tab === "market") loadMarket();
    else if (tab === "approvals") loadApprovals("pending");
    else if (tab === "audit") loadAudit();
    else if (tab === "config") loadConfig();
  }
  function loadReferences() {
    const box = $("refList"); box.innerHTML = "";
    references.forEach(r => {
      box.appendChild(item({
        name: r.name + " (" + r.kind + ")", meta: r.uri, badge: r.enabled ? "启用" : "停用", badgeOff: !r.enabled,
        rows: [
          btn("读", async () => { try { const x = await rpc("reference.read", { id: r.id }); openModal("参考: " + r.name, { nodes: [h("pre", { class: "input", style: "height:240px", text: (x.payload && x.payload.content) || "" })] }, [btn("关闭", closeModal)]); } catch (e) { toast("读取失败"); } }),
          btn(r.enabled ? "停用" : "启用", async () => { try { await rpc("reference.setEnabled", { id: r.id, enabled: !r.enabled }); references = (await rpc("reference.list", {})).payload.references || []; loadReferences(); } catch (e) {} }),
          btn("移除", async () => { if (!confirm("移除 " + r.name + "?")) return; try { await rpc("reference.remove", { id: r.id }); references = (await rpc("reference.list", {})).payload.references || []; loadReferences(); } catch (e) {} })
        ]
      }));
    });
    if (!references.length) emptyList(box);
  }
  function btn(label, fn, cls) { const b = h("button", { class: "ghost sm" + (cls ? " " + cls : ""), text: label }); b.onclick = fn; return b; }
  function loadIntegrations() {
    const box = $("intList"); box.innerHTML = "";
    rpc("integration.list", {}).then(r => {
      (r.payload && r.payload.integrations || []).forEach(it => {
        box.appendChild(item({
          name: it.name + " (" + it.kind + ")", meta: it.id, badge: it.status, badgeOff: it.status !== "connected",
          rows: [
            btn("配 Key", async () => { const k = prompt("API Key："); if (!k) return; try { await rpc("integration.connect.key", { id: it.id, key: k }); loadIntegrations(); } catch (e) { toast("失败"); } })
          ]
        }));
      });
      if (!box.children.length) emptyList(box);
    }).catch(() => emptyList(box));
  }
  function renderArtifacts(list) {
    const box = $("artList"); box.innerHTML = "";
    (list || []).forEach(a => box.appendChild(item({
      name: a.name + " · " + a.kind, meta: a.path || a.mime || "",
      rows: [btn("移除", async () => { try { await rpc("artifact.remove", { id: a.id }); renderArtifacts((await rpc("artifact.list", { sessionId: currentSession })).payload.artifacts || []); } catch (e) {} })]
    })));
    if (!box.children.length) emptyList(box);
  }
  function loadHooks() {
    const box = $("hookList"); box.innerHTML = "";
    rpc("hooks.list", {}).then(r => {
      (r.payload && r.payload.hooks || []).forEach(hk => box.appendChild(item({
        name: hk.event + " · " + (hk.command || "").slice(0, 40), meta: "优先级 " + (hk.priority || 0),
        badge: hk.enabled ? "启用" : "停用", badgeOff: !hk.enabled,
        rows: [
          btn(hk.enabled ? "停用" : "启用", async () => { try { await rpc("hooks.setEnabled", { id: hk.id, enabled: !hk.enabled }); loadHooks(); } catch (e) {} }),
          btn("移除", async () => { if (confirm("移除?")) { try { await rpc("hooks.remove", { id: hk.id }); loadHooks(); } catch (e) {} } })
        ]
      })));
      if (!box.children.length) emptyList(box);
    }).catch(() => emptyList(box));
  }
  function loadSkills() {
    const box = $("skillList"); box.innerHTML = "";
    rpc("skills.list", {}).then(r => {
      (r.payload && r.payload.skills || []).forEach(s => box.appendChild(item({
        name: s.name, meta: (s.description || "").slice(0, 80), badge: s.enabled ? "启用" : "停用", badgeOff: !s.enabled,
        onclick: () => { rpc("skills.setEnabled", { name: s.name, enabled: !s.enabled }).then(loadSkills).catch(() => {}); }
      })));
      if (!box.children.length) emptyList(box);
    }).catch(() => emptyList(box));
  }
  function loadPlugins() {
    const box = $("pluginList"); box.innerHTML = "";
    rpc("plugins.list", {}).then(r => {
      (r.payload && r.payload.plugins || []).forEach(p => box.appendChild(item({
        name: p.name + " v" + (p.version || ""), meta: (p.source || "") + " · " + (p.description || ""),
        badge: p.enabled ? "启用" : "停用", badgeOff: !p.enabled,
        rows: [
          btn(p.enabled ? "停用" : "启用", async () => { try { await rpc("plugins.setEnabled", { id: p.id, enabled: !p.enabled }); loadPlugins(); } catch (e) {} }),
          btn("卸载", async () => { if (confirm("卸载?")) { try { await rpc("plugins.uninstall", { id: p.id }); loadPlugins(); } catch (e) {} } })
        ]
      })));
      if (!box.children.length) emptyList(box);
    }).catch(() => emptyList(box));
  }
  function loadMarket() {
    const box = $("marketList"); box.innerHTML = "";
    rpc("plugins.marketplace", {}).then(r => {
      (r.payload && r.payload.plugins || []).forEach(p => box.appendChild(item({
        name: p.name + " v" + (p.version || ""), meta: p.description || p.source || "",
        rows: [btn("安装", async () => { try { await rpc("plugins.install", { name: p.name, source: p.source, version: p.version }); toast("已安装: " + p.name); loadMarket(); } catch (e) { toast("安装失败"); } })]
      })));
      if (!box.children.length) emptyList(box, "市场为空");
    }).catch(() => emptyList(box, "市场加载失败"));
  }
  function loadApprovals(which) {
    if (which === "exec") { loadExecPolicy(); return; }
    const box = which === "pending" ? $("approvalList") : $("approvalHistory");
    const other = which === "pending" ? $("approvalHistory") : $("approvalList");
    box.classList.remove("hidden"); other.classList.add("hidden"); $("approvalExec").classList.add("hidden");
    rpc(which === "pending" ? "approval.list" : "approval.history", which === "pending" ? { status: "pending" } : {}).then(r => {
      box.innerHTML = "";
      const list = (r.payload && (r.payload.approvals || r.payload.history)) || [];
      list.forEach(a => {
        const rows = [];
        if (which === "pending" && a.status === "pending") {
          rows.push(btn("通过", async () => { try { await rpc("approval.resolve", { id: a.id, decision: "approve" }); loadApprovals("pending"); } catch (e) {} }, "primary"));
          rows.push(btn("拒绝", async () => { try { await rpc("approval.resolve", { id: a.id, decision: "reject", reason: "rejected" }); loadApprovals("pending"); } catch (e) {} }, "deny"));
        }
        box.appendChild(item({
          name: a.kind + " · " + (a.status || ""), meta: (a.requester || "") + " — " + JSON.stringify(a.payload || {}).slice(0, 100),
          rows
        }));
      });
      if (!box.children.length) emptyList(box, which === "pending" ? "无待办审批" : "无历史");
    }).catch(() => emptyList(box));
  }
  function loadExecPolicy() {
    $("approvalList").classList.add("hidden"); $("approvalHistory").classList.add("hidden");
    const box = $("approvalExec"); box.classList.remove("hidden"); box.innerHTML = "";
    rpc("exec.approvals.get", { scope: "gateway" }).then(r => {
      const pol = (r.payload && r.payload.policy) || {};
      const ta = field("默认策略 (allow/ask/deny)", pol.default || "ask");
      const pa = field("模式 (local/remote)", pol.mode || "local");
      const save = btn("保存", async () => { try { await rpc("exec.approvals.set", { scope: "gateway", policy: JSON.stringify({ default: ta.inp.value, mode: pa.inp.value }) }); toast("已保存执行策略"); } catch (e) { toast("失败"); } }, "primary");
      box.appendChild(ta.wrap); box.appendChild(pa.wrap); box.appendChild(h("div", { class: "row" }, save));
    }).catch(() => emptyList(box, "加载失败"));
  }
  function loadAudit() {
    const box = $("auditList"); box.innerHTML = "";
    rpc("audit.activity.list", { limit: 80 }).then(r => {
      const list = (r.payload && (r.payload.events || r.payload.activities)) || [];
      list.forEach(e => box.appendChild(item({ name: (e.kind || e.action || "") + " · " + (e.result || ""), meta: new Date((e.ts || 0)).toLocaleString() + " · " + (e.actor || "") })));
      if (!box.children.length) emptyList(box);
    }).catch(() => emptyList(box));
  }
  function loadConfig() {
    const box = $("cfgList"); box.innerHTML = "";
    rpc("config.list", {}).then(r => {
      (r.payload && r.payload.entries || []).forEach(en => {
        const inp = h("input", { class: "input", value: en.value != null ? en.value : "" });
        inp.onchange = () => rpc("config.set", { path: en.path, value: inp.value }).catch(() => toast("保存失败"));
        const it = h("div", { class: "item" }, h("div", { class: "name", text: en.path }),
          h("div", { class: "meta", text: (en.description || "") + " [" + (en.reloadKind || "") + "]" }), inp);
        box.appendChild(it);
      });
      if (!box.children.length) emptyList(box);
    }).catch(() => emptyList(box));
  }

  /* ---------- 看板 ---------- */
  const BOARD_COLS = ["triage", "backlog", "todo", "scheduled", "ready", "running", "review", "blocked", "done"];
  async function loadBoard() {
    const board = $("board"); board.innerHTML = "";
    let cards = [];
    try { const r = await rpc("workboard.cards.list", { boardId: "main" }); cards = r.payload.cards || []; } catch (e) { toast("看板加载失败"); }
    BOARD_COLS.forEach(col => {
      const colEl = h("div", { class: "board-col", dataset: { col } });
      colEl.appendChild(h("div", { class: "col-head" }, h("span", { text: col }), h("span", { text: String(cards.filter(c => c.status === col).length) })));
      const body = h("div", { class: "col-body" });
      cards.filter(c => c.status === col).forEach(c => body.appendChild(renderCard(c)));
      colEl.appendChild(body);
      colEl.addEventListener("dragover", (e) => { e.preventDefault(); colEl.classList.add("drag-over"); });
      colEl.addEventListener("dragleave", () => colEl.classList.remove("drag-over"));
      colEl.addEventListener("drop", async (e) => { e.preventDefault(); colEl.classList.remove("drag-over");
        const id = e.dataTransfer.getData("text/plain"); if (id) { try { await rpc("workboard.cards.move", { cardId: id, status: col }); } catch (err) { toast("移动失败"); } } });
      board.appendChild(colEl);
    });
  }
  function renderCard(c) {
    const el = h("div", { class: "card", draggable: "true" });
    el.appendChild(h("div", { class: "c-title" }, document.createTextNode(c.title), h("span", { class: "c-prio prio-" + (c.priority || "normal"), text: c.priority || "normal" })));
    if (c.description) el.appendChild(h("div", { class: "c-meta", text: c.description.slice(0, 80) }));
    if (c.assignedAgentId) el.appendChild(h("div", { class: "c-meta", text: "🤖 " + c.assignedAgentId }));
    el.addEventListener("dragstart", (e) => e.dataTransfer.setData("text/plain", c.id));
    el.addEventListener("click", () => showCardDetail(c));
    return el;
  }
  async function showCardDetail(c) {
    const evs = []; try { const r = await rpc("workboard.cards.events", { cardId: c.id }); evs.push(...(r.payload.events || [])); } catch (e) {}
    const body = h("div", {});
    body.appendChild(h("div", { class: "kv", html: "<b>状态</b> " + c.status + " · <b>优先级</b> " + (c.priority || "normal") }));
    body.appendChild(h("div", { class: "kv", html: "<b>描述</b> " + esc(c.description || "") }));
    if (c.assignedAgentId) body.appendChild(h("div", { class: "kv", html: "<b>指派</b> " + esc(c.assignedAgentId) }));
    if (c.linkedTaskId) body.appendChild(h("div", { class: "kv", html: "<b>关联任务</b> " + esc(c.linkedTaskId) }));
    if (c.linkedSessionKey) body.appendChild(h("div", { class: "kv", html: "<b>关联会话</b> " + esc(c.linkedSessionKey) }));
    body.appendChild(h("div", { class: "kv", text: "事件历史 (" + evs.length + ")" }));
    evs.forEach(e => body.appendChild(h("div", { class: "meta", text: (e.kind || "") + " · " + new Date(e.createdAt || 0).toLocaleString() + " · " + (e.actor || "") })));
    const foot = [btn("移动状态", () => {
      const sel = h("select", { class: "select" }); BOARD_COLS.forEach(s => sel.appendChild(h("option", { value: s, text: s }))); sel.value = c.status;
      openModal("移动卡片: " + c.title, { nodes: [formRow("目标状态", sel)] }, [
        btn("确定", async () => { try { await rpc("workboard.cards.move", { cardId: c.id, status: sel.value }); closeModal(); loadBoard(); } catch (e2) { toast("失败"); } }, "primary"),
        btn("取消", closeModal)
      ]);
    }), btn("编辑", () => editCard(c)), btn("删除", async () => { if (confirm("删除卡片?")) { try { await rpc("workboard.cards.delete", { cardId: c.id }); closeModal(); loadBoard(); } catch (e2) {} } }, "deny"), btn("关闭", closeModal)];
    openModal("卡片: " + c.title, { nodes: [body] }, foot);
  }
  function editCard(c) {
    const title = field("标题", c.title);
    const desc = field("描述", c.description);
    const prio = h("select", { class: "select" }); ["low", "normal", "high", "urgent"].forEach(p => prio.appendChild(h("option", { value: p, text: p }))); prio.value = c.priority || "normal";
    openModal("编辑卡片", { nodes: [title.wrap, desc.wrap, formRow("优先级", prio)] }, [
      btn("保存", async () => { try { await rpc("workboard.cards.update", { cardId: c.id, title: title.inp.value, description: desc.inp.value, priority: prio.value }); closeModal(); loadBoard(); } catch (e) { toast("失败"); } }, "primary"),
      btn("取消", closeModal)
    ]);
  }
  function newCard() {
    const title = field("标题*", "");
    const desc = field("描述", "");
    const status = h("select", { class: "select" }); BOARD_COLS.forEach(s => status.appendChild(h("option", { value: s, text: s }))); status.value = "triage";
    const prio = h("select", { class: "select" }); ["low", "normal", "high", "urgent"].forEach(p => prio.appendChild(h("option", { value: p, text: p }))); prio.value = "normal";
    openModal("新建卡片", { nodes: [title.wrap, desc.wrap, formRow("状态", status), formRow("优先级", prio)] }, [
      btn("创建", async () => { if (!title.inp.value.trim()) { toast("标题必填"); return; } try { await rpc("workboard.cards.create", { boardId: "main", title: title.inp.value, description: desc.inp.value, status: status.value, priority: prio.value }); closeModal(); loadBoard(); } catch (e) { toast("创建失败"); } }, "primary"),
      btn("取消", closeModal)
    ]);
  }

  /* ---------- 任务 ---------- */
  async function loadTasks() {
    const box = $("tasks"); box.innerHTML = "";
    let tasks = []; try { const r = await rpc("tasks.list", {}); tasks = r.payload.tasks || []; } catch (e) { toast("任务加载失败"); }
    if (!tasks.length) { emptyList(box, "暂无任务"); return; }
    const tbl = h("table", { class: "tbl" });
    tbl.appendChild(trh(["ID", "标签", "状态", "运行时", "智能体", "进度", "操作"]));
    tasks.forEach(t => {
      const tr = h("tr");
      tr.appendChild(h("td", { text: (t.id || "").slice(0, 10) }));
      tr.appendChild(h("td", { text: t.label || "" }));
      tr.appendChild(h("td", {}, h("span", { class: "pill" + (t.status === "failed" ? " warn" : t.status === "running" ? " info" : ""), text: t.status || "" })));
      tr.appendChild(h("td", { text: t.runtime || "" }));
      tr.appendChild(h("td", { text: t.agentId || "" }));
      tr.appendChild(h("td", { text: (t.progressSummary || t.terminalSummary || "").slice(0, 40) }));
      const op = h("td"); op.appendChild(btn("详情", () => showTask(t.id)));
      if (t.status === "running" || t.status === "queued") op.appendChild(btn("取消", async () => { try { await rpc("tasks.cancel", { taskId: t.id }); loadTasks(); } catch (e) { toast("取消失败"); } }, "deny"));
      tr.appendChild(op); tbl.appendChild(tr);
    });
    box.appendChild(tbl);
  }
  async function showTask(id) {
    const box = $("taskDetail"); box.classList.remove("hidden"); box.innerHTML = "";
    try { const r = await rpc("tasks.get", { taskId: id }); const t = r.payload;
      const kv = (k, v) => h("div", { class: "kv", html: "<b>" + k + "</b> " + esc(v == null ? "" : v) });
      box.appendChild(kv("ID", t.id)); box.appendChild(kv("标签", t.label)); box.appendChild(kv("状态", t.status));
      box.appendChild(kv("运行时", t.runtime)); box.appendChild(kv("智能体", t.agentId)); box.appendChild(kv("源", t.sourceId));
      box.appendChild(kv("进度", t.progressSummary || "")); box.appendChild(kv("结果", t.terminalSummary || ""));
      if (t.error) box.appendChild(kv("错误", t.error));
      box.appendChild(kv("详情", JSON.stringify(t.detail || {}).slice(0, 200)));
      box.appendChild(h("div", { class: "row" }, btn("关闭", () => box.classList.add("hidden"))));
    } catch (e) { box.appendChild(h("div", { class: "empty", text: "加载失败" })); }
  }

  /* ---------- 调度 ---------- */
  async function loadCron() {
    const box = $("cron"); box.innerHTML = ""; $("cronRuns").classList.add("hidden");
    let jobs = []; try { const r = await rpc("cron.list", {}); jobs = r.payload.jobs || []; } catch (e) { toast("调度加载失败"); }
    if (!jobs.length) { emptyList(box, "暂无调度任务"); return; }
    const tbl = h("table", { class: "tbl" });
    tbl.appendChild(trh(["名称", "智能体", "计划", "启用", "下次触发", "操作"]));
    jobs.forEach(j => {
      const tr = h("tr");
      tr.appendChild(h("td", { text: j.name }));
      tr.appendChild(h("td", { text: j.agentId || "" }));
      tr.appendChild(h("td", { text: j.schedule }));
      tr.appendChild(h("td", {}, h("span", { class: "pill" + (j.enabled ? "" : " warn"), text: j.enabled ? "是" : "否" })));
      tr.appendChild(h("td", { text: j.nextFireAt ? new Date(j.nextFireAt).toLocaleString() : "-" }));
      const op = h("td");
      op.appendChild(btn("运行", async () => { try { await rpc("cron.run", { jobId: j.id }); toast("已触发"); } catch (e) { toast("失败"); } }, "primary"));
      op.appendChild(btn("记录", () => showRuns(j.id)));
      op.appendChild(btn("编辑", () => editCron(j)));
      op.appendChild(btn("删", async () => { if (confirm("删除?")) { try { await rpc("cron.remove", { jobId: j.id }); loadCron(); } catch (e) {} } }, "deny"));
      tr.appendChild(op); tbl.appendChild(tr);
    });
    box.appendChild(tbl);
  }
  async function showRuns(id) {
    const box = $("cronRuns"); box.classList.remove("hidden"); box.innerHTML = "<h4>运行记录</h4>";
    try { const r = await rpc("cron.runs", { jobId: id }); const runs = r.payload.runs || [];
      const tbl = h("table", { class: "tbl" });
      tbl.appendChild(trh(["状态", "触发时间", "开始", "结束", "RunId", "错误"]));
      runs.forEach(rn => tbl.appendChild(h("tr", {},
        h("td", {}, h("span", { class: "pill" + (rn.status === "failed" ? " warn" : ""), text: rn.status || "" })),
        h("td", { text: rn.fireAt ? new Date(rn.fireAt).toLocaleString() : "" }),
        h("td", { text: rn.startedAt ? new Date(rn.startedAt).toLocaleString() : "" }),
        h("td", { text: rn.endedAt ? new Date(rn.endedAt).toLocaleString() : "" }),
        h("td", { text: (rn.runId || "").slice(0, 10) }),
        h("td", { text: (rn.error || "").slice(0, 40) }))));
      box.appendChild(tbl);
    } catch (e) { box.appendChild(h("div", { class: "empty", text: "加载失败" })); }
  }
  function editCron(j) {
    const name = field("名称", j.name);
    const agent = field("智能体", j.agentId);
    const sched = field("计划(cron 表达式 6 段: 秒 分 时 日 月 周)", j.schedule);
    const prompt = field("提示词", j.prompt);
    const en = h("select", { class: "select" }); ["true", "false"].forEach(v => en.appendChild(h("option", { value: v, text: v }))); en.value = String(!!j.enabled);
    openModal("编辑调度", { nodes: [name.wrap, agent.wrap, sched.wrap, prompt.wrap, formRow("启用", en)] }, [
      btn("保存", async () => { try { await rpc("cron.update", { jobId: j.id, name: name.inp.value, agentId: agent.inp.value, schedule: sched.inp.value, prompt: prompt.inp.value, enabled: en.value === "true" }); closeModal(); loadCron(); } catch (e) { toast("保存失败"); } }, "primary"),
      btn("取消", closeModal)
    ]);
  }
  function agentSelect(defaultVal) {
    const sel = h("select", { class: "select" });
    const list = agents && agents.length ? agents : [{ id: "main", name: "main" }];
    list.forEach(a => sel.appendChild(h("option", { value: a.id, text: (a.emoji || "") + " " + (a.name || a.id) })));
    if (defaultVal) sel.value = defaultVal;
    return sel;
  }
  function newCron() {
    const name = field("名称*", "");
    const agent = agentSelect(currentAgent);
    const sched = field("计划(cron 表达式 6 段: 秒 分 时 日 月 周, 如 0 0 9 * * *)", "");
    const prompt = field("提示词*", "");
    openModal("新建调度", { nodes: [name.wrap, formRow("智能体*", agent), sched.wrap, prompt.wrap] }, [
      btn("创建", async () => { if (!name.inp.value.trim() || !prompt.inp.value.trim()) { toast("名称与提示词必填"); return; } try { await rpc("cron.add", { agentId: agent.value, name: name.inp.value, schedule: sched.inp.value, prompt: prompt.inp.value, sessionPolicy: "reuse" }); closeModal(); loadCron(); } catch (e) { toast("创建失败: " + (e.message || e.code)); } }, "primary"),
      btn("取消", closeModal)
    ]);
  }

  /* ---------- 记忆 ---------- */
  async function memSearch() {
    const q = $("memQuery").value.trim(); if (!q) return;
    const box = $("memResults"); box.innerHTML = "";
    try {
      const r = await rpc("memory.search", { query: q, limit: 20 });
      (r.payload.results || []).forEach(m => box.appendChild(item({
        name: (m.content || "").slice(0, 100),
        meta: "类: " + (m.originClass || "") + " · " + new Date(m.createdAt || 0).toLocaleString()
      })));
      if (!box.children.length) emptyList(box, "无结果");
    } catch (e) { emptyList(box, "搜索失败"); }
  }
  async function loadMemRecent() {
    const box = $("memRecentList"); box.innerHTML = "";
    try { const r = await rpc("memory.recent", { days: 7, limit: 30 }); (r.payload.results || []).forEach(m => box.appendChild(item({ name: (m.content || "").slice(0, 100), meta: "类: " + (m.originClass || "") + " · " + new Date(m.createdAt || 0).toLocaleString() }))); if (!box.children.length) emptyList(box); }
    catch (e) {}
  }
  async function loadMemCurated() {
    const box = $("memCuratedList"); box.innerHTML = "";
    try { const r = await rpc("memory.curated", {}); (r.payload.results || []).forEach(m => box.appendChild(item({ name: (m.content || "").slice(0, 100), meta: new Date(m.createdAt || 0).toLocaleString() }))); if (!box.children.length) emptyList(box); }
    catch (e) {}
  }
  async function memSweep() {
    try { const r = await rpc("dreaming.sweep", {}); const p = r.payload || {}; toast("梦境巡检: 审阅 " + (p.reviewed || 0) + " 提升 " + (p.promoted || 0));
      openModal("梦境巡检报告", { nodes: [h("pre", { class: "input", style: "height:240px", text: p.report || "" })] }, [btn("关闭", closeModal)]); }
    catch (e) { toast("巡检失败"); }
  }

  /* ---------- 用量 ---------- */
  async function loadUsage(which) {
    document.querySelectorAll(".mem-tabs [data-usagetab]").forEach(b => b.classList.toggle("on", b.dataset.usagetab === which));
    ["agent", "daily", "session"].forEach(w => $("usage" + w[0].toUpperCase() + w.slice(1)).classList.toggle("hidden", w !== which));
    if (which === "agent") {
      const box = $("usageAgent"); box.innerHTML = "";
      try { const r = await rpc("usage.byAgent", {}); const rows = r.payload.agents || [];
        const tbl = h("table", { class: "tbl" }); tbl.appendChild(trh(["智能体", "输入", "输出", "成本", "会话数"]));
        rows.forEach(a => tbl.appendChild(h("tr", {}, h("td", { text: a.agentId }), h("td", { text: a.totalInput }), h("td", { text: a.totalOutput }), h("td", { text: "$" + (a.totalCost || 0).toFixed(4) }), h("td", { text: a.sessionCount }))));
        box.appendChild(tbl); if (!rows.length) emptyList(box);
      } catch (e) { emptyList(box); }
    } else if (which === "daily") {
      const box = $("usageDaily"); box.innerHTML = "";
      try { const r = await rpc("usage.daily", { days: 30 }); const rows = r.payload.daily || [];
        let max = 1; rows.forEach(d => { const c = d.totalCost || 0; if (c > max) max = c; });
        const tbl = h("table", { class: "tbl" }); tbl.appendChild(trh(["日期", "成本", "输入", "输出", "会话数"]));
        rows.forEach(d => { const tr = h("tr"); tr.appendChild(h("td", { text: d.date }));
          const bar = h("div", { class: "bar", style: "width:" + Math.round((d.totalCost || 0) / max * 100) + "%" });
          tr.appendChild(h("td", {}, h("div", { text: "$" + (d.totalCost || 0).toFixed(4) }), bar));
          tr.appendChild(h("td", { text: d.totalInput })); tr.appendChild(h("td", { text: d.totalOutput })); tr.appendChild(h("td", { text: d.sessionCount }));
          tbl.appendChild(tr); });
        box.appendChild(tbl); if (!rows.length) emptyList(box);
      } catch (e) { emptyList(box); }
    } else {
      const box = $("usageSession"); box.innerHTML = "";
      try { const r = await rpc("usage.sessions", {}); const rows = r.payload.sessions || [];
        const tbl = h("table", { class: "tbl" }); tbl.appendChild(trh(["会话", "智能体", "输入", "输出", "成本", "更新"]));
        rows.forEach(s => tbl.appendChild(h("tr", {}, h("td", { text: (s.sessionKey || "").slice(0, 16) }), h("td", { text: s.agentId }), h("td", { text: s.tokensInput }), h("td", { text: s.tokensOutput }), h("td", { text: "$" + (s.cost || 0).toFixed(4) }), h("td", { text: s.updatedAt ? new Date(s.updatedAt).toLocaleString() : "" }))));
        box.appendChild(tbl); if (!rows.length) emptyList(box);
      } catch (e) { emptyList(box); }
    }
  }

  /* ---------- 频道 ---------- */
  async function loadChannels() {
    const box = $("channels"); box.innerHTML = "";
    try { const r = await rpc("channels.bindings.list", {}); const list = r.payload.bindings || [];
      const tbl = h("table", { class: "tbl" }); tbl.appendChild(trh(["频道", "账号", "智能体", "ID", "操作"]));
      list.forEach(b => { const tr = h("tr"); tr.appendChild(h("td", { text: b.channel })); tr.appendChild(h("td", { text: b.accountId }));
        tr.appendChild(h("td", { text: b.agentId || "" })); tr.appendChild(h("td", { text: (b.id || "").slice(0, 12) }));
        const op = h("td"); op.appendChild(btn("删", async () => { if (confirm("解绑?")) { try { await rpc("channels.bindings.remove", { bindingId: b.id }); loadChannels(); } catch (e) {} } }, "deny")); tr.appendChild(op); tbl.appendChild(tr); });
      box.appendChild(tbl); if (!list.length) emptyList(box, "暂无频道绑定");
    } catch (e) { emptyList(box, "加载失败"); }
  }
  function newChannel() {
    const channel = h("select", { class: "select" }); ["wecom", "dingtalk", "feishu", "webhook"].forEach(c => channel.appendChild(h("option", { value: c, text: c })));
    const account = field("账号 ID", "");
    const agent = agentSelect(currentAgent);
    const cfg = field("配置(JSON, 可选)", "{}");
    openModal("绑定频道", { nodes: [formRow("频道", channel), account.wrap, formRow("智能体", agent), cfg.wrap] }, [
      btn("绑定", async () => { try { await rpc("channels.bindings.create", { channel: channel.value, accountId: account.inp.value, agentId: agent.inp.value, config: cfg.inp.value || "{}" }); closeModal(); loadChannels(); } catch (e) { toast("绑定失败: " + (e.message || e.code)); } }, "primary"),
      btn("取消", closeModal)
    ]);
  }

  /* ---------- 设备 ---------- */
  async function loadDevices() {
    const pend = $("devicePending"); pend.innerHTML = "";
    const listBox = $("deviceList"); listBox.innerHTML = "";
    try {
      const rp = await rpc("device.pair.status", {}); const pending = rp.payload.pending || [];
      if (!pending.length) emptyList(pend, "无待配对");
      pending.forEach(p => { const it = item({ name: p.deviceId || p.id, meta: "scopes: " + (p.scopes || ""),
        rows: [btn("批准", async () => { try { await rpc("device.pair.approve", { pairingId: p.id, role: "developer" }); loadDevices(); } catch (e) {} }, "primary"),
               btn("拒绝", async () => { try { await rpc("device.pair.reject", { pairingId: p.id }); loadDevices(); } catch (e) {} }, "deny")] }); pend.appendChild(it); });
    } catch (e) { emptyList(pend); }
    try {
      const rl = await rpc("device.list", {}); const devs = rl.payload.devices || [];
      if (!devs.length) emptyList(listBox, "无设备");
      devs.forEach(d => listBox.appendChild(item({ name: d.label || d.id, meta: "角色: " + (d.role || "") + " · 平台: " + (d.platform || "") + " · 访问: " + (d.access || ""),
        rows: [btn("改名", async () => { const n = prompt("新名称：", d.label); if (n) { try { await rpc("device.rename", { deviceId: d.id, label: n }); loadDevices(); } catch (e) {} } }),
               btn("吊销", async () => { if (confirm("吊销设备?")) { try { await rpc("device.revoke", { deviceId: d.id }); loadDevices(); } catch (e) {} } }, "deny")] })));
    } catch (e) { emptyList(listBox); }
  }

  /* ---------- 管理：用户 / 智能体 / 分享 ---------- */
  async function loadUsers() {
    const box = $("userList"); box.innerHTML = "";
    try { const r = await rpc("auth.users.list", {}); (r.payload.users || []).forEach(u => box.appendChild(item({
      name: u.username + (u.displayName ? " (" + u.displayName + ")" : ""), meta: "角色: " + (u.role || "") + " · 状态: " + (u.status || ""),
      rows: [btn("吊销令牌", async () => { if (confirm("吊销该用户全部令牌?")) { /* 这里仅示例，实际按 token 吊销 */ toast("请在用户详情实现令牌吊销"); } })]
    }))); if (!box.children.length) emptyList(box, "无用户"); } catch (e) { emptyList(box); }
  }
  function newUser() {
    const user = field("用户名*", "");
    const pass = field("密码*", "");
    const disp = field("显示名", "");
    const role = h("select", { class: "select" }); ["admin", "developer", "viewer"].forEach(r => role.appendChild(h("option", { value: r, text: r }))); role.value = "developer";
    openModal("新建用户", { nodes: [user.wrap, pass.wrap, disp.wrap, formRow("角色", role)] }, [
      btn("创建", async () => { if (!user.inp.value.trim() || !pass.inp.value) { toast("用户名密码必填"); return; } try { await rpc("auth.users.create", { username: user.inp.value, password: pass.inp.value, displayName: disp.inp.value, role: role.value }); closeModal(); loadUsers(); } catch (e) { toast("创建失败: " + (e.message || e.code)); } }, "primary"),
      btn("取消", closeModal)
    ]);
  }
  async function loadAgentsAdmin() {
    const box = $("agentList"); box.innerHTML = "";
    (agents.length ? agents : (await rpc("agents.list", {}).then(r => r.payload.agents || []).catch(() => []))).forEach(a => box.appendChild(item({
      name: (a.emoji || "🤖") + " " + a.name, meta: "角色: " + (a.role || "") + " · 模型: " + (a.modelId || "") + " · 工具: " + (a.allowedTools || []).join(",")
    })));
    if (!box.children.length) emptyList(box);
  }
  function newAgent() {
    const name = field("名称*", "");
    const role = h("select", { class: "select" });
    ["developer", "reviewer", "tester", "pm", "ops", "approver", "knowledge", "admin"].forEach(r => role.appendChild(h("option", { value: r, text: r })));
    const emoji = field("图标", "🤖");
    const prov = field("模型提供方", "openai");
    const model = field("模型 ID", "gpt-4o");
    openModal("新建智能体", { nodes: [name.wrap, formRow("角色*", role), emoji.wrap, formRow("模型提供方", prov.inp), formRow("模型 ID", model.inp)] }, [
      btn("创建", async () => { if (!name.inp.value.trim()) { toast("名称必填"); return; } try { await rpc("agents.create", { name: name.inp.value, role: role.value, emoji: emoji.inp.value, modelProvider: prov.inp.value, modelId: model.inp.value }); closeModal(); loadAgentsAdmin(); } catch (e) { toast("创建失败: " + (e.message || e.code)); } }, "primary"),
      btn("取消", closeModal)
    ]);
  }
  async function shareCurrent() {
    try { const r = await rpc("share.create", { sessionKey: currentSession }); const token = r.payload && r.payload.token;
      if (!token) { toast("分享创建失败"); return; }
      $("shareUrl").textContent = location.origin + "/share/" + token; $("shareModal").classList.remove("hidden");
    } catch (e) { toast("分享失败"); }
  }

  /* ---------- 命令面板 ---------- */
  function openPalette(mode, items) {
    paletteMode = mode; paletteItems = items; paletteSel = 0;
    $("palette").classList.remove("hidden"); $("paletteInput").value = ""; $("paletteInput").focus(); renderPalette("");
  }
  function renderPalette(q) {
    const box = $("paletteItems"); box.innerHTML = "";
    const items = paletteItems.filter(it => ((it.label || "") + " " + (it.cat || "")).toLowerCase().includes((q || "").toLowerCase()));
    items.forEach((it, i) => {
      const d = h("div", { class: "palette-item" + (i === paletteSel ? " sel" : "") }, h("span", { text: it.label }), h("span", { class: "cat", text: it.cat || "" }));
      d.onclick = () => choosePalette(it); box.appendChild(d);
    });
    if (!items.length) box.appendChild(h("div", { class: "empty", text: "无匹配" }));
  }
  function choosePalette(it) {
    $("palette").classList.add("hidden");
    if (paletteMode === "at") { $("input").value = $("input").value.replace(/@\S*$/, "@" + it.label + " "); $("input").focus(); return; }
    if (paletteMode === "slash") { $("input").value = it.label + " "; $("input").focus(); return; }
    if (it.act === "view") switchView(it.target);
    else if (it.label && it.label.startsWith("/")) runCommand(it.label);
    else if (it.act) it.act();
  }
  function buildPalette(mode) {
    if (mode === "cmd") {
      const items = commands.map(c => ({ label: c.slashName || ("/" + c.id), cat: c.category, act: () => runCommand(c.slashName || ("/" + c.id)) }));
      const local = [{ label: "/share", cat: "builtin" }, { label: "/new", cat: "builtin" }, { label: "/clear", cat: "builtin" }];
      const views = ["chat", "board", "tasks", "cron", "memory", "usage", "channels", "devices", "admin"].map(v => ({ label: "视图: " + v, cat: "view", act: () => switchView(v) }));
      openPalette("cmd", items.concat(local).concat(views));
    } else if (mode === "slash") {
      const items = commands.map(c => ({ label: c.slashName || ("/" + c.id), cat: c.category })).concat([{ label: "/share", cat: "builtin" }, { label: "/new", cat: "builtin" }, { label: "/clear", cat: "builtin" }]);
      openPalette("slash", items);
    } else if (mode === "at") {
      const sItems = skills.map(s => ({ label: s.name, cat: "skill" }));
      const rItems = references.filter(r => r.enabled).map(r => ({ label: r.name, cat: "ref" }));
      openPalette("at", sItems.concat(rItems));
    }
  }

  /* ---------- 鉴权模态 ---------- */
  function showAuth(noUsers) {
    $("authTitle").textContent = noUsers ? "初始化管理员" : "登录";
    const body = $("authBody"); body.innerHTML = "";
    const user = field("用户名", noUsers ? "admin" : "");
    const pass = field("密码", "");
    body.appendChild(user.wrap); body.appendChild(pass.wrap);
    let disp = null;
    if (noUsers) { disp = field("显示名", "Admin"); body.appendChild(disp.wrap); }
    const foot = $("authFoot"); foot.innerHTML = "";
    foot.appendChild(btn(noUsers ? "创建并登录" : "登录", async () => {
      try {
        const r = noUsers
          ? await rpc("auth.bootstrap", { username: user.inp.value, password: pass.inp.value, displayName: disp ? disp.inp.value : "" })
          : await rpc("auth.login", { username: user.inp.value, password: pass.inp.value });
        const token = r.payload && r.payload.token;
        if (token) localStorage.setItem("lobster_token", token);
        authed = true; $("authModal").classList.add("hidden");
        onReady();
      } catch (e) { toast("失败: " + (e.message || e.code)); }
    }, "primary"));
    $("authModal").classList.remove("hidden");
  }

  /* ---------- 事件绑定 ---------- */
  function bind() {
    $("send").onclick = submit;
    $("input").addEventListener("keydown", (e) => {
      if (e.key === "Enter") { submit(); return; }
      if (e.key === "Tab" && ($("input").value.startsWith("/") || $("input").value.includes("@"))) { e.preventDefault(); buildPalette($("input").value.startsWith("/") ? "slash" : "at"); }
    });
    $("input").addEventListener("input", () => {
      const v = $("input").value;
      if (v === "/") buildPalette("slash");
      else if (v.startsWith("@") || /\s@\S*$/.test(v)) buildPalette("at");
    });
    $("planToggle").onclick = togglePlan;
    $("paletteBtn").onclick = () => buildPalette("cmd");
    $("paletteInput").addEventListener("input", (e) => renderPalette(e.target.value));
    $("paletteInput").addEventListener("keydown", (e) => {
      const items = $("paletteItems").children;
      if (e.key === "ArrowDown") { paletteSel = Math.min(paletteSel + 1, items.length - 1); renderPalette($("paletteInput").value); }
      else if (e.key === "ArrowUp") { paletteSel = Math.max(paletteSel - 1, 0); renderPalette($("paletteInput").value); }
      else if (e.key === "Enter") { const arr = paletteItems.filter(x => ((x.label || "") + " " + (x.cat || "")).toLowerCase().includes($("paletteInput").value.toLowerCase())); const it = arr[paletteSel]; if (it) choosePalette(it); }
      else if (e.key === "Escape") { $("palette").classList.add("hidden"); }
    });
    document.addEventListener("keydown", (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") { e.preventDefault(); buildPalette("cmd"); }
    });
    $("viewsNav").addEventListener("click", (e) => { const b = e.target.closest(".vnav"); if (b) switchView(b.dataset.view); });

    // 会话操作
    $("newSession").onclick = () => newSession();
    $("renameSession").onclick = () => renameSession();
    $("forkSession").onclick = () => forkSession();
    $("rewindSession").onclick = () => rewindSession();
    $("archiveSession").onclick = () => archiveSession();
    $("queueMode").onchange = (e) => setQueueMode(e.target.value);

    // inspector 切换
    document.querySelectorAll("#inspectorTabs .tab").forEach(t => t.onclick = () => openInspectorTab(t.dataset.tab));
    document.querySelector('[data-act="ref-add"]').onclick = async () => { const name = prompt("参考库名称："); if (!name) return; const uri = prompt("URI（local/git/url）：", "https://"); if (!uri) return; try { await rpc("reference.install", { name, kind: "url", uri }); references = (await rpc("reference.list", {})).payload.references || []; loadReferences(); } catch (e) { toast("挂载失败"); } };
    document.querySelector('[data-act="int-add"]').onclick = async () => { const name = prompt("集成名称："); if (!name) return; const kind = prompt("类型（oauth/key）：", "key"); if (!kind) return; const key = kind === "key" ? (prompt("API Key：") || "") : ""; try { await rpc("integration.install", { name, kind, key }); loadIntegrations(); } catch (e) { toast("连接失败"); } };
    document.querySelector('[data-act="art-add"]').onclick = async () => { const name = prompt("产物名称："); if (!name) return; const path = prompt("路径：") || ""; const mime = prompt("MIME：", "text/plain") || "text/plain"; try { await rpc("artifact.attach", { sessionId: currentSession, name, path, mime }); renderArtifacts((await rpc("artifact.list", { sessionId: currentSession })).payload.artifacts || []); } catch (e) { toast("附加失败"); } };
    document.querySelector('[data-act="hook-add"]').onclick = async () => { const event = prompt("触发事件（如 tool.before / agent.run.ended）："); if (!event) return; const command = prompt("执行命令："); if (!command) return; try { await rpc("hooks.install", { scope: "global", scopeId: "global", event, kind: "command", command, timeoutMs: 5000 }); loadHooks(); sys("已安装钩子: " + event); } catch (e) { toast("安装失败"); } };
    document.querySelector('[data-act="market-refresh"]').onclick = loadMarket;
    document.querySelector('[data-act="approval-refresh"]').onclick = () => loadApprovals("pending");
    document.querySelector('[data-act="audit-refresh"]').onclick = loadAudit;
    // approval 子标签
    document.querySelectorAll('[data-astab]').forEach(b => b.onclick = () => { document.querySelectorAll('[data-astab]').forEach(x => x.classList.toggle("on", x === b)); loadApprovals(b.dataset.astab); });
    // 看板
    $("boardAdd").onclick = newCard;
    $("boardRefresh").onclick = loadBoard;
    // 任务
    $("tasksRefresh").onclick = loadTasks;
    // 调度
    $("cronAdd").onclick = newCron;
    // 记忆
    document.querySelectorAll('[data-memtab]').forEach(b => b.onclick = () => { document.querySelectorAll('[data-memtab]').forEach(x => x.classList.toggle("on", x === b)); document.getElementById("mem" + b.dataset.memtab[0].toUpperCase() + b.dataset.memtab.slice(1)).classList.toggle("hidden", false); document.querySelectorAll(".mem-pane").forEach(p => p.classList.add("hidden")); document.getElementById("mem" + b.dataset.memtab[0].toUpperCase() + b.dataset.memtab.slice(1)).classList.remove("hidden"); });
    $("memSearchBtn").onclick = memSearch;
    $("memQuery").addEventListener("keydown", (e) => { if (e.key === "Enter") memSearch(); });
    $("memorySweep").onclick = memSweep;
    // 用量
    document.querySelectorAll('[data-usagetab]').forEach(b => b.onclick = () => loadUsage(b.dataset.usagetab));
    // 频道
    $("channelAdd").onclick = newChannel;
    // 设备
    $("deviceRefresh").onclick = loadDevices;
    // 管理
    document.querySelectorAll("#adminTabs .tab").forEach(t => t.onclick = () => { document.querySelectorAll("#adminTabs .tab").forEach(x => x.classList.remove("on")); t.classList.add("on"); document.querySelectorAll("#atab-users,#atab-agents,#atab-share").forEach(p => p.classList.add("hidden")); $("atab-" + t.dataset.atab).classList.remove("hidden"); });
    document.querySelector('[data-act="user-add"]').onclick = newUser;
    document.querySelector('[data-act="agent-add"]').onclick = newAgent;
    document.querySelector('[data-act="share-now"]').onclick = shareCurrent;
    // 分享模态
    $("shareClose").onclick = () => $("shareModal").classList.add("hidden");
    $("shareCopy").onclick = () => navigator.clipboard.writeText($("shareUrl").textContent);
    // 权限
    $("permAlways").onclick = () => respondPermission("ALLOW_ALWAYS");
    $("permOnce").onclick = () => respondPermission("ALLOW_ONCE");
    $("permReject").onclick = () => respondPermission("REJECT");
    // 模态点击空白关闭
    $("modal").addEventListener("click", (e) => { if (e.target === $("modal")) closeModal(); });
    $("permModal").addEventListener("click", (e) => { if (e.target === $("permModal")) hidePermission(); });
    $("authModal").addEventListener("click", (e) => { if (e.target === $("authModal")) { /* 不强制关闭，登录需要 */ } });
  }

  /* ---------- 启动 ---------- */
  bind();
  connect();
})();
