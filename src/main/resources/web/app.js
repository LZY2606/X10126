"use strict";

const $ = (id) => document.getElementById(id);
const state = {
  definitions: [],
  sessions: [],
  checkpoints: [],
  currentSession: null,
  selectedCheckpoint: null,
};

const sampleDefinition = {
  name: "支付与风控状态机",
  initialState: "idle",
  initialVars: { balance: 100, flagged: false, attempts: 0 },
  transitions: [
    {
      event: "pay",
      from: "idle",
      to: "paid",
      condition: "e.data.amount <= balance && !flagged",
      actions: [
        { type: "set", target: "balance", valueExpr: "balance - e.data.amount" },
        { type: "output", name: "receipt", dataExpr: "{ amount: e.data.amount, balance: balance }" },
        { type: "emit", event: "audit", source: "ledger", timeDelta: 0,
          dataExpr: "{ kind: 'paid', amount: e.data.amount }" }
      ]
    },
    {
      event: "pay",
      from: "idle",
      to: "blocked",
      condition: "e.data.amount > balance || flagged",
      actions: [
        { type: "set", target: "attempts", valueExpr: "attempts + 1" },
        { type: "fail", messageExpr: "'blocked: insufficient balance or flagged'" }
      ]
    },
    {
      event: "audit",
      actions: [
        { type: "output", name: "audit-log", dataExpr: "{ event: e.name, at: t, detail: e.data }" }
      ]
    },
    {
      event: "reset",
      actions: [
        { type: "set", target: "flagged", valueExpr: "false" },
        { type: "set", target: "attempts", valueExpr: "0" }
      ]
    },
    {
      event: "flag",
      actions: [{ type: "set", target: "flagged", valueExpr: "true" }]
    }
  ]
};

const sampleEvents = [
  { id: "pay-1", name: "pay", source: "shop", time: 10, seq: 1, priority: 10, data: { amount: 30 } },
  { id: "flag-1", name: "flag", source: "risk", time: 10, seq: 1, priority: 20, data: {} },
  { id: "pay-2", name: "pay", source: "shop", time: 20, seq: 2, priority: 10, data: { amount: 999 } },
  { id: "reset-1", name: "reset", source: "ops", time: 30, seq: 1, priority: 5, data: {} }
];

async function api(path, options = {}) {
  const response = await fetch(path, {
    method: options.method || "GET",
    headers: options.body ? { "Content-Type": "application/json" } : {},
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  const text = await response.text();
  let payload = null;
  try { payload = text ? JSON.parse(text) : null; } catch (e) { payload = { raw: text }; }
  if (!response.ok) {
    const error = new Error((payload && (payload.error || payload.detail)) || response.statusText);
    error.payload = payload;
    error.status = response.status;
    throw error;
  }
  return payload;
}

function jsonPretty(value) {
  try { return JSON.stringify(value, null, 2); } catch (e) { return String(value); }
}

function escapeHtml(value) {
  return String(value == null ? "" : value)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function renderDefinitionFingerprint(definition) {
  if (!definition) {
    $("definition-fingerprint").textContent = "";
    return;
  }
  $("definition-fingerprint").textContent =
    "定义指纹: " + definition.fingerprint + "\n初始状态: " + definition.initialState;
}

async function refreshDefinitions() {
  const data = await api("/api/definitions");
  state.definitions = data.definitions || [];
  const select = $("definition-select");
  select.innerHTML = "";
  for (const definition of state.definitions) {
    const option = document.createElement("option");
    option.value = definition.fingerprint;
    option.textContent = (definition.name || "未命名") + " (" + definition.fingerprint.slice(0, 10) + ")";
    select.appendChild(option);
  }
}

async function refreshSessions() {
  const data = await api("/api/sessions");
  state.sessions = data.sessions || [];
  for (const id of ["session-select", "compare-a", "compare-b"]) {
    const select = $(id);
    const previous = select.value;
    select.innerHTML = "";
    for (const session of state.sessions) {
      const option = document.createElement("option");
      option.value = session.id;
      option.textContent = session.name + " [" + session.steps + "步] (" + session.id.slice(0, 10) + ")";
      select.appendChild(option);
    }
    if (previous) select.value = previous;
  }
  if (!$("session-select").value && state.sessions.length) {
    $("session-select").value = state.sessions[0].id;
    await selectSession();
  }
}

async function refreshCheckpoints() {
  const sessions = await api("/api/sessions");
  const all = new Map();
  for (const session of sessions.sessions || []) {
    const detail = await api("/api/sessions/" + session.id);
    for (const entry of detail.trace || []) {
      // checkpoints are created by user action; list is kept locally below
    }
  }
  const select = $("checkpoint-select");
  select.innerHTML = "";
  for (const checkpoint of state.checkpoints) {
    const option = document.createElement("option");
    option.value = checkpoint.id;
    option.textContent = checkpoint.id + " @ step " + checkpoint.step
      + " (" + checkpoint.definitionFingerprint.slice(0, 8) + ")";
    select.appendChild(option);
  }
}

function renderSession(session) {
  state.currentSession = session;
  const meta = $("session-meta");
  meta.innerHTML = "";
  const entries = [
    ["会话", session.name + " / " + session.id],
    ["定义指纹", session.definitionFingerprint],
    ["锁定指纹", session.lockFingerprint],
    ["轨迹哈希", session.traceHeadHash || "(尚无)"],
    ["当前状态", session.currentState],
    ["变量", jsonPretty(session.vars)],
    ["待处理", String(session.pending)],
    ["祖先检查点", session.parentCheckpointId || "无"],
  ];
  for (const [label, value] of entries) {
    const div = document.createElement("div");
    div.innerHTML = "<span>" + label + "</span><code>" + escapeHtml(value) + "</code>";
    meta.appendChild(div);
  }

  const pending = $("pending-list");
  pending.innerHTML = "";
  for (const event of session.pendingEvents || []) {
    const div = document.createElement("div");
    div.className = "event-item" + (event.kind === "internal" ? " internal" : "");
    div.innerHTML = "<strong>t=" + event.time + "</strong> "
      + escapeHtml(event.name)
      + " <span class='kv'>source=" + escapeHtml(event.source)
      + " priority=" + event.priority + " seq=" + event.seq
      + (event.parentId ? " parent=" + escapeHtml(event.parentId) : "") + "</span>"
      + " <span class='badge'>" + event.kind + "</span>";
    pending.appendChild(div);
  }

  const trace = $("trace-list");
  trace.innerHTML = "";
  for (const step of (session.trace || []).slice().reverse()) {
    const div = document.createElement("div");
    div.className = "trace-item " + step.outcome;
    const outputs = (step.outputs || []).map((o) => escapeHtml(o.name)
      + " " + escapeHtml(jsonPretty(o.data))).join("; ") || "-";
    div.innerHTML =
      "<div>第 " + step.step + " 步 · t=" + step.event.time + " · "
      + escapeHtml(step.event.name)
      + " <span class='badge " + step.outcome + "'>" + step.outcome + "</span></div>"
      + "<div class='kv'>状态: " + escapeHtml(JSON.stringify(step.stateBefore).slice(0, 120))
      + " → " + escapeHtml(JSON.stringify(step.stateAfter).slice(0, 120)) + "</div>"
      + "<div class='kv'>输出: " + outputs + "</div>"
      + (step.failure ? "<div class='kv'>失败记录: " + escapeHtml(step.failure) + "</div>" : "")
      + "<details><summary>前后差异 / 派生内部事件 / 哈希</summary>"
      + "<pre>" + escapeHtml(jsonPretty({
          before: step.stateBefore,
          after: step.stateAfter,
          outputs: step.outputs,
          emitted: step.emitted,
          hash: step.hash,
        })) + "</pre></details>";
    trace.appendChild(div);
  }
}

async function selectSession() {
  const id = $("session-select").value;
  if (!id) return;
  const session = await api("/api/sessions/" + id);
  renderSession(session);
}

function currentDefinitionFingerprint() {
  const select = $("definition-select");
  return select.value || (state.definitions[0] && state.definitions[0].fingerprint);
}

function bindEvents() {
  $("load-sample").onclick = () => {
    $("definition-editor").value = jsonPretty(sampleDefinition);
    $("event-editor").value = jsonPretty(sampleEvents);
  };

  $("save-definition").onclick = async () => {
    try {
      const parsed = JSON.parse($("definition-editor").value);
      const saved = await api("/api/definitions", { method: "POST", body: parsed });
      renderDefinitionFingerprint(saved);
      await refreshDefinitions();
      $("definition-select").value = saved.fingerprint;
    } catch (e) {
      alert("保存失败: " + e.message);
    }
  };

  $("load-definition").onclick = async () => {
    const fingerprint = $("definition-select").value;
    if (!fingerprint) return;
    const definition = await api("/api/definitions/" + fingerprint);
    delete definition.fingerprint;
    $("definition-editor").value = jsonPretty(definition);
    renderDefinitionFingerprint({ fingerprint });
  };

  $("create-session").onclick = async () => {
    try {
      const fingerprint = currentDefinitionFingerprint();
      if (!fingerprint) throw new Error("请先保存定义");
      const events = JSON.parse($("event-editor").value || "[]");
      const session = await api("/api/sessions", {
        method: "POST",
        body: {
          definitionFingerprint: fingerprint,
          seed: Number($("seed").value || 0),
          name: $("session-name").value,
          events,
        },
      });
      await refreshSessions();
      $("session-select").value = session.id;
      renderSession(session);
    } catch (e) {
      alert("创建会话失败: " + e.message);
    }
  };

  $("refresh-session").onclick = async () => { await selectSession(); };

  async function step(count) {
    const id = $("session-select").value;
    const session = await api("/api/sessions/" + id + "/step", {
      method: "POST", body: { count },
    });
    renderSession(session);
    await refreshSessions();
    $("session-select").value = id;
  }
  $("step").onclick = () => step(1);
  $("step5").onclick = () => step(5);
  $("run-end").onclick = () => step(100000);

  $("checkpoint").onclick = async () => {
    const id = $("session-select").value;
    const checkpoint = await api("/api/sessions/" + id + "/checkpoints", { method: "POST", body: {} });
    state.checkpoints.push(checkpoint);
    await refreshCheckpoints();
    $("checkpoint-select").value = checkpoint.id;
    alert("已建立检查点 " + checkpoint.id + "（定义指纹已锁定）");
  };

  $("append-events").onclick = async () => {
    const id = $("session-select").value;
    const events = JSON.parse($("event-editor").value || "[]");
    const session = await api("/api/sessions/" + id + "/events", { method: "POST", body: { events } });
    renderSession(session);
  };

  async function fork(useOther) {
    const checkpointId = $("checkpoint-select").value;
    if (!checkpointId) return alert("请先选择检查点");
    const fingerprints = state.definitions.map((d) => d.fingerprint);
    const checkpoint = state.checkpoints.find((c) => c.id === checkpointId);
    let definitionFingerprint = checkpoint.definitionFingerprint;
    if (useOther) {
      definitionFingerprint = fingerprints.find((f) => f !== checkpoint.definitionFingerprint);
      if (!definitionFingerprint) return alert("没有另一个定义可用于验证拒绝场景");
    }
    try {
      const session = await api("/api/checkpoints/" + checkpointId + "/fork", {
        method: "POST",
        body: { definitionFingerprint, name: $("branch-name").value },
      });
      await refreshSessions();
      $("session-select").value = session.id;
      renderSession(session);
    } catch (e) {
      alert("分叉被拒绝: HTTP " + e.status + "\n" + e.message + "\n" + jsonPretty(e.payload));
    }
  }
  $("fork").onclick = () => fork(false);
  $("fork-new-def").onclick = () => fork(true);

  $("compare").onclick = async () => {
    const a = $("compare-a").value;
    const b = $("compare-b").value;
    const result = await api("/api/compare/" + a + "/" + b);
    $("compare-result").textContent = jsonPretty(result);
  };

  $("merge").onclick = async () => {
    try {
      const result = await api("/api/merge", {
        method: "POST",
        body: { a: $("compare-a").value, b: $("compare-b").value, name: "合并结果" },
      });
      $("merge-result").textContent = "合并成功，轨迹哈希: " + result.traceHeadHash
        + "\n" + jsonPretty({ id: result.id, currentState: result.state, vars: result.vars });
      await refreshSessions();
    } catch (e) {
      $("merge-result").textContent = "合并被拒绝: " + e.message + "\n" + jsonPretty(e.payload);
    }
  };

  $("export-session").onclick = async () => {
    const id = $("session-select").value;
    const payload = await api("/api/sessions/" + id + "/export");
    $("io-editor").value = jsonPretty(payload);
  };

  $("import-session").onclick = async () => {
    try {
      const body = JSON.parse($("io-editor").value);
      const session = await api("/api/sessions/import", { method: "POST", body });
      await refreshDefinitions();
      await refreshSessions();
      $("session-select").value = session.id;
      renderSession(session);
      alert("导入成功，轨迹哈希: " + session.traceHeadHash);
    } catch (e) {
      alert("导入失败: " + e.message);
    }
  };
}

async function init() {
  $("definition-editor").value = jsonPretty(sampleDefinition);
  $("event-editor").value = jsonPretty(sampleEvents);
  bindEvents();
  await refreshDefinitions();
  await refreshSessions();
  if (state.sessions.length) {
    $("session-select").value = state.sessions[0].id;
    await selectSession();
  }
}

init();
