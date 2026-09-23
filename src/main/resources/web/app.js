let current = null;

const SAMPLE_DEF = {
  name: "订单状态机", version: 1,
  states: ["idle", "waiting", "paid", "done"],
  initialState: "idle",
  sourcePriorities: { "ui": 10, "gateway": 20, "timer": 30 },
  transitions: [
    { event: "submit", from: "idle", condition: "", to: "waiting",
      actions: [ {type:"set", var:"amount", expr:"payload.amount"},
                 {type:"output", message:"提交金额 ${amount}"} ] },
    { event: "pay", from: "waiting", condition: "payload.amount >= amount", to: "paid",
      actions: [ {type:"output", message:"支付成功"}, {type:"emit", event:"ship"} ] },
    { event: "pay", from: "waiting", condition: "payload.amount < amount", to: "waiting",
      actions: [ {type:"fail", message:"支付金额不足"} ] },
    { event: "ship", from: "paid", to: "done",
      actions: [ {type:"random", var:"lottery", bound:100},
                 {type:"output", message:"已发货，抽奖号 ${lottery}"} ] }
  ]
};
const SAMPLE_EVENTS = [
  { name:"submit", time:1, source:"ui", seq:1, payload:{amount:100} },
  { name:"pay", time:2, source:"ui", seq:2, payload:{amount:50} },
  { name:"pay", time:2, source:"gateway", seq:1, payload:{amount:100} }
];

function api(path, opts={}) {
  return fetch("/api/sessions" + path, {
    method: opts.method || "GET",
    headers: { "Content-Type": "application/json" },
    body: opts.body ? JSON.stringify(opts.body) : undefined
  }).then(async r => {
    const data = await r.json();
    if (!r.ok) throw new Error(data.error + (data.conflict
        ? "\n冲突事件 A: " + JSON.stringify(data.conflict.a)
        + "\n冲突事件 B: " + JSON.stringify(data.conflict.b) : ""));
    return data;
  });
}
function toast(msg) {
  const t = document.getElementById("toast");
  const d = document.createElement("div"); d.textContent = msg;
  t.appendChild(d); setTimeout(() => d.remove(), 9000);
}
function esc(v) {
  return String(v ?? "").replace(/[&<>"]/g,
    c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
}
function loadSample() { document.getElementById("def").value = JSON.stringify(SAMPLE_DEF, null, 2); }
function sampleEvents() { document.getElementById("events").value = JSON.stringify(SAMPLE_EVENTS, null, 2); }
function toggleImport() {
  const b = document.getElementById("importbox");
  b.style.display = b.style.display === "none" ? "block" : "none";
}

async function guard(fn) { try { await fn(); } catch (e) { toast(e.message); } }

function createSession() { return guard(async () => {
  const definition = JSON.parse(document.getElementById("def").value);
  const seed = Number(document.getElementById("seed").value || "0");
  const name = document.getElementById("sname").value;
  const st = await api("", { method:"POST", body:{ definition, seed, name } });
  current = st.id; await refresh();
});}
function addEvents() { return guard(async () => {
  const events = JSON.parse(document.getElementById("events").value);
  await api("/" + current + "/events", { method:"POST", body:{ events } });
  await refresh();
});}
function doStep() { return guard(async () => {
  await api("/" + current + "/step", { method:"POST" }); await refresh();
});}
function doRun(n) { return guard(async () => {
  await api("/" + current + "/run", { method:"POST", body:{ count:n } }); await refresh();
});}
function addCheckpoint() { return guard(async () => {
  await api("/" + current + "/checkpoints",
    { method:"POST", body:{ name: document.getElementById("cpName").value } });
  await refresh();
});}
function branch(cpId) { return guard(async () => {
  const name = prompt("分支名称");
  const st = await api("/" + current + "/branch",
    { method:"POST", body:{ checkpointId:cpId, name } });
  current = st.id; await refresh();
});}
function doMerge() { return guard(async () => {
  const a = document.getElementById("selA").value;
  const b = document.getElementById("selB").value;
  const st = await api("/" + a + "/merge", { method:"POST", body:{ other:b } });
  current = st.id; await refresh();
  alert("合并成功，新会话: " + st.name + " (" + st.id + ")");
});}
function compare() { return guard(async () => {
  const a = document.getElementById("selA").value, b = document.getElementById("selB").value;
  const d = await api("/" + a + "/compare?other=" + b);
  document.getElementById("compare").innerHTML =
    `<table>
      <tr><th>状态一致</th><td><span class="pill ${d.stateEqual?'ok':'fail'}">${d.stateEqual}</span></td></tr>
      <tr><th>输出一致</th><td><span class="pill ${d.outputsEqual?'ok':'fail'}">${d.outputsEqual}</span></td></tr>
      <tr><th>A 轨迹</th><td class="mono hash">${esc(d.a.traceHash)}</td></tr>
      <tr><th>B 轨迹</th><td class="mono hash">${esc(d.b.traceHash)}</td></tr>
      <tr><th>A 输出</th><td class="mono">${esc(d.outputsA.join("\n"))||"—"}</td></tr>
      <tr><th>B 输出</th><td class="mono">${esc(d.outputsB.join("\n"))||"—"}</td></tr>
    </table>`;
});}
function exportSession() { return guard(async () => {
  const d = await api("/" + current + "/export");
  document.getElementById("importbox").style.display = "block";
  document.getElementById("importtext").value = JSON.stringify(d, null, 2);
});}
function importSession() { return guard(async () => {
  const doc = JSON.parse(document.getElementById("importtext").value);
  const st = await api("/import", { method:"POST", body:doc });
  current = st.id; await refresh();
});}

function renderStatus(st) {
  document.getElementById("curName").textContent = st.name + " (" + st.id + ")";
  document.getElementById("stSteps").textContent = st.stepCount;
  document.getElementById("stPending").textContent = st.pendingCount;
  document.getElementById("stState").textContent = st.snapshot.state;
  document.getElementById("stVars").textContent = JSON.stringify(st.snapshot.vars);
  document.getElementById("stNext").textContent = st.nextEvent
      ? `t=${st.nextEvent.time} ${st.nextEvent.source}#${st.nextEvent.seq} ${st.nextEvent.name}` : "—";
  document.getElementById("stDefFp").textContent = st.definitionFingerprint;
  document.getElementById("stFp").textContent = st.fingerprint;
  document.getElementById("stTrace").textContent = st.traceHash;
}

function diffHtml(before, after) {
  const parts = [];
  if (before.state !== after.state) {
    parts.push(`<span class="diff-mut">state: ${esc(before.state)} → ${esc(after.state)}</span>`);
  }
  const keys = new Set([...Object.keys(before.vars||{}), ...Object.keys(after.vars||{})]);
  for (const k of keys) {
    const b = (before.vars||{})[k], a = (after.vars||{})[k];
    if (!(k in (before.vars||{}))) parts.push(`<span class="diff-add">+${esc(k)}=${esc(JSON.stringify(a))}</span>`);
    else if (!(k in (after.vars||{}))) parts.push(`<span class="diff-del">-${esc(k)}</span>`);
    else if (JSON.stringify(b) !== JSON.stringify(a))
      parts.push(`<span class="diff-mut">${esc(k)}: ${esc(JSON.stringify(b))} → ${esc(JSON.stringify(a))}</span>`);
  }
  return parts.length ? parts.join("<br>") : '<span class="muted">（无变化）</span>';
}

function renderTrace(trace) {
  const el = document.getElementById("trace");
  el.innerHTML = trace.map(s => {
    const ev = s.event;
    const badge = s.failed ? '<span class="pill fail">失败已回滚</span>'
        : s.guardRejected ? '<span class="pill">条件未满足</span>'
        : '<span class="pill ok">已应用</span>';
    const outs = (s.outputs||[]).map(o => `<div class="diff-add">▸ ${esc(o)}</div>`).join("");
    const emitted = (s.emitted||[]).map(e =>
        `<div class="tag">↳ 内部事件 ${esc(e.name)} (t=${e.time})</div>`).join("");
    const err = s.failed ? `<div class="diff-del">✕ ${esc(s.error)}</div>` : "";
    return `<div class="step">
      <div class="head"><b>#${s.index} ${esc(ev.name)}</b>
        <span class="tag">t=${ev.time} ${esc(ev.source)}#${ev.seq}${ev.internal?" ·内部":""}</span>
        ${badge}</div>
      <div class="mono">${diffHtml(s.before, s.after)}</div>
      ${outs}${emitted}${err}
    </div>`;
  }).reverse().join("") || '<p class="muted">尚无步骤，先导入事件并单步。</p>';
}

async function refresh() { return guard(async () => {
  const list = await api("");
  const sessions = list.sessions;
  if (!current && sessions.length) current = sessions[0].id;
  const opts = sessions.map(s =>
      `<option value="${s.id}" ${s.id===current?"selected":""}>${esc(s.name)} (${s.id})</option>`).join("");
  document.getElementById("selA").innerHTML = opts;
  document.getElementById("selB").innerHTML = opts;
  document.getElementById("sessions").innerHTML =
      "<tr><th>会话</th><th>步骤</th><th>待处理</th><th>来源</th></tr>" +
      sessions.map(s => `<tr>
        <td class="mono">${s.id===current?"▶ ":""}<a href="#" onclick="current='${s.id}';refresh();return false">${esc(s.name)}</a></td>
        <td>${s.stepCount}</td><td>${s.pendingCount}</td>
        <td class="tag">${s.branchPoint ? "分叉自 " + esc(s.branchPoint.sessionId) + "@" + s.branchPoint.stepIndex : "根"}</td>
      </tr>`).join("");
  if (!current) return;
  const st = await api("/" + current);
  renderStatus(st);
  const cps = await api("/" + current + "/checkpoints");
  document.getElementById("cps").innerHTML =
      "<tr><th>#</th><th>名称</th><th>步骤</th><th></th></tr>" +
      cps.checkpoints.map(c => `<tr><td class="mono">${esc(c.id)}</td>
        <td>${esc(c.name)}</td><td>${c.stepIndex}</td>
        <td><button class="ghost" onclick="branch('${c.id}')">分叉</button></td></tr>`).join("");
  const tr = await api("/" + current + "/trace");
  renderTrace(tr.trace);
});}

loadSample();
sampleEvents();
refresh();
