package replay;

/** Single-page web UI, served at GET /. */
public final class Ui {
    private Ui() {}

    public static final String PAGE = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>状态机回放室</title>
<style>
  :root { --bg:#0f1420; --panel:#1a2233; --ink:#dbe4f5; --dim:#8b98b5; --acc:#5aa9ff; --ok:#4cc38a; --bad:#ff6b6b; }
  * { box-sizing: border-box; }
  body { margin:0; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; background:var(--bg); color:var(--ink); }
  header { padding:14px 20px; border-bottom:1px solid #2a3550; display:flex; align-items:baseline; gap:14px; }
  header h1 { font-size:18px; margin:0; color:var(--acc); }
  header span { color:var(--dim); font-size:12px; }
  main { display:grid; grid-template-columns: 1fr 1fr 1fr; gap:12px; padding:12px 20px; }
  section { background:var(--panel); border:1px solid #2a3550; border-radius:8px; padding:12px; min-width:0; }
  h2 { font-size:13px; margin:0 0 8px; color:var(--dim); text-transform:uppercase; letter-spacing:1px; }
  textarea { width:100%; background:#0b0f1a; color:var(--ink); border:1px solid #2a3550; border-radius:6px;
             font-family:inherit; font-size:12px; padding:8px; resize:vertical; }
  button { background:var(--acc); color:#06101f; border:0; border-radius:6px; padding:6px 12px;
           font-family:inherit; font-size:12px; font-weight:600; cursor:pointer; margin:2px 4px 2px 0; }
  button.ghost { background:#2a3550; color:var(--ink); }
  button.warn { background:var(--bad); }
  select, input { background:#0b0f1a; color:var(--ink); border:1px solid #2a3550; border-radius:6px;
                  padding:5px 8px; font-family:inherit; font-size:12px; margin:2px 4px 2px 0; }
  pre { background:#0b0f1a; border-radius:6px; padding:8px; font-size:11px; overflow:auto; max-height:260px; margin:6px 0; }
  .row { display:flex; flex-wrap:wrap; align-items:center; gap:4px; margin:6px 0; }
  .pill { display:inline-block; padding:2px 8px; border-radius:10px; font-size:11px; background:#2a3550; margin:2px; cursor:pointer; }
  .pill.active { background:var(--acc); color:#06101f; }
  .ok { color:var(--ok); } .bad { color:var(--bad); } .dim { color:var(--dim); }
  .diff-add { color:var(--ok); } .diff-del { color:var(--bad); }
  table { border-collapse:collapse; width:100%; font-size:11px; }
  td, th { border:1px solid #2a3550; padding:3px 6px; text-align:left; }
  #trace { max-height:340px; overflow:auto; }
  .step { border-bottom:1px solid #232d45; padding:6px 4px; font-size:11px; }
  .step b { color:var(--acc); }
</style>
</head>
<body>
<header>
  <h1>状态机回放室</h1>
  <span>确定性状态机回放 · 检查点 · 分叉与合并 · 稳定指纹</span>
</header>
<main>
  <section>
    <h2>状态机定义</h2>
    <textarea id="defText" rows="18"></textarea>
    <div class="row">
      <button id="saveDef">保存定义（新版本）</button>
      <span id="defInfo" class="dim"></span>
    </div>
    <h2>事件导入</h2>
    <textarea id="eventsText" rows="8"></textarea>
    <div class="row">
      <input id="seed" type="number" value="42" style="width:90px" title="随机种子">
      <input id="branchName" value="main" style="width:90px" title="分支名">
      <button id="createSession">创建会话</button>
      <button id="appendEvents" class="ghost">追加事件</button>
    </div>
    <h2>分支</h2>
    <div id="branchList"></div>
  </section>
  <section>
    <h2>回放控制</h2>
    <div class="row">
      <button id="stepBtn">单步 ▶</button>
      <button id="runBtn" class="ghost">播放到底 ⏩</button>
      <button id="cpBtn" class="ghost">建立检查点</button>
      <button id="refreshBtn" class="ghost">刷新</button>
    </div>
    <div id="curInfo"></div>
    <h2>待处理事件</h2>
    <pre id="pending"></pre>
    <h2>轨迹（每步前后差异）</h2>
    <div id="trace"></div>
  </section>
  <section>
    <h2>检查点 / 分叉 / 合并</h2>
    <div id="cpList"></div>
    <div class="row">
      <select id="mergeFrom"></select>
      <button id="mergeBtn" class="warn">合并进当前分支</button>
    </div>
    <div class="row">
      <button id="exportBtn" class="ghost">导出会话</button>
      <button id="importBtn" class="ghost">导入会话</button>
    </div>
    <textarea id="exportText" rows="6" placeholder="导出/导入的会话 JSON"></textarea>
    <h2>分支比较</h2>
    <div class="row"><select id="diffOther"></select><button id="diffBtn" class="ghost">比较</button></div>
    <pre id="diffOut"></pre>
    <h2>消息</h2>
    <pre id="msg"></pre>
  </section>
</main>
<script>
const SAMPLE_DEF = {
  id: "cell-gateway", version: 1, initialState: "IDLE",
  initialVariables: { count: 0, retries: 0 },
  sources: { operator: 0, sensor: 1 },
  transitions: [
    { event: "START", from: ["IDLE"],
      condition: { var: "count", op: ">=", value: 0 },
      actions: [ { type: "setState", state: "RUNNING" },
                 { type: "emit", name: "started" },
                 { type: "raise", event: "TICK", payload: { n: 1 } } ] },
    { event: "TICK",
      actions: [ { type: "increment", var: "count", by: 1 },
                 { type: "emit", name: "ticked", data: { src: "auto" } } ] },
    { event: "BOOM",
      actions: [ { type: "setState", state: "BROKEN" },
                 { type: "raise", event: "ALARM" },
                 { type: "fail", message: "boom handler exploded" } ] },
    { event: "STOP", from: ["RUNNING"],
      actions: [ { type: "setState", state: "IDLE" }, { type: "emit", name: "stopped" } ] }
  ]
};
const SAMPLE_EVENTS = [
  { id: "e1", time: 10, source: "sensor", seq: 2, name: "START", payload: {} },
  { id: "e2", time: 10, source: "operator", seq: 1, name: "PING", payload: {} },
  { id: "e3", time: 11, source: "sensor", seq: 1, name: "BOOM", payload: {} },
  { id: "e4", time: 12, source: "operator", seq: 3, name: "STOP", payload: {} }
];

const $ = id => document.getElementById(id);
let currentBranch = null;

$("defText").value = JSON.stringify(SAMPLE_DEF, null, 2);
$("eventsText").value = JSON.stringify(SAMPLE_EVENTS, null, 2);

async function api(method, path, body) {
  const res = await fetch(path, { method, headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body) });
  const data = await res.json();
  if (!res.ok) { const e = new Error(data.message || res.statusText); e.data = data; throw e; }
  return data;
}
function showMsg(o) { $("msg").textContent = typeof o === "string" ? o : JSON.stringify(o, null, 2); }
async function guard(fn) { try { await fn(); } catch (e) { showMsg(e.data || String(e)); } }

async function refresh() {
  const st = await api("GET", "/api/state");
  $("branchList").innerHTML = "";
  $("mergeFrom").innerHTML = ""; $("diffOther").innerHTML = "";
  for (const b of st.branches) {
    const el = document.createElement("span");
    el.className = "pill" + (b.id === currentBranch ? " active" : "");
    el.textContent = `${b.name}(${b.id}) ${b.state}#${b.stepCount}`;
    el.onclick = () => { currentBranch = b.id; refresh(); };
    $("branchList").appendChild(el);
    for (const sel of [$("mergeFrom"), $("diffOther")]) {
      const o = document.createElement("option");
      o.value = b.id; o.textContent = `${b.name}(${b.id})`;
      sel.appendChild(o);
    }
  }
  if (!currentBranch && st.branches.length) currentBranch = st.branches[0].id;
  if (st.definitions.length) {
    const d = st.definitions[st.definitions.length - 1];
    $("defInfo").textContent = `${d.id} v${d.version} fp=${d.fingerprint.slice(0,12)}…`;
  }
  if (currentBranch) await refreshBranch();
}

async function refreshBranch() {
  const d = await api("GET", "/api/branches/" + currentBranch);
  $("curInfo").innerHTML = `<table>
    <tr><th>状态</th><td><b>${d.state}</b></td><th>步数</th><td>${d.stepCount}</td></tr>
    <tr><th>变量</th><td colspan="3">${JSON.stringify(d.vars)}</td></tr>
    <tr><th>会话指纹</th><td colspan="3" class="dim">${d.fingerprint}</td></tr>
    <tr><th>轨迹哈希</th><td colspan="3" class="dim">${d.trajectoryHash}</td></tr></table>`;
  $("pending").textContent = d.pendingEvents.map(e =>
    `t=${e.time} src=${e.source} seq=${e.seq} ${e.name}(${e.id})`).join("\\n") || "(空)";
  const tr = $("trace"); tr.innerHTML = "";
  for (const e of d.trace.slice().reverse()) {
    const div = document.createElement("div");
    div.className = "step";
    const varsDiff = diffVars(e.varsBefore, e.varsAfter);
    div.innerHTML = `<b>#${e.step}</b> t=${e.time} ${e.eventName} <span class="dim">${e.eventId}${e.internal?" ·内部":""}</span>
      ${e.matched ? "" : '<span class="dim">(无匹配)</span>'}
      ${e.failure ? `<span class="bad">✗ ${e.failure}（已回滚）</span>` : ""}
      <br><span class="diff-del">${e.stateBefore}</span> → <span class="diff-add">${e.stateAfter}</span>
      ${varsDiff ? `<br>${varsDiff}` : ""}
      ${e.outputs && e.outputs.length ? `<br>输出: ${JSON.stringify(e.outputs)}` : ""}`;
    tr.appendChild(div);
  }
  const cp = $("cpList"); cp.innerHTML = "";
  for (const c of d.checkpoints) {
    const el = document.createElement("div");
    el.className = "row";
    el.innerHTML = `<span class="pill">${c.name}(${c.id}) @步${c.step}</span>`;
    const forkB = document.createElement("button"); forkB.textContent = "分叉"; forkB.className = "ghost";
    forkB.onclick = () => guard(async () => {
      const f = await api("POST", `/api/branches/${currentBranch}/fork`,
        { checkpointId: c.id, name: "fork-" + c.name });
      currentBranch = f.id; showMsg("已分叉: " + f.id); refresh();
    });
    const resB = document.createElement("button"); resB.textContent = "回滚"; resB.className = "ghost";
    resB.onclick = () => guard(async () => {
      await api("POST", `/api/branches/${currentBranch}/restore`, { checkpointId: c.id });
      showMsg("已回滚到检查点 " + c.name); refresh();
    });
    el.appendChild(forkB); el.appendChild(resB);
    cp.appendChild(el);
  }
}

function diffVars(a, b) {
  const keys = new Set([...Object.keys(a||{}), ...Object.keys(b||{})]);
  const parts = [];
  for (const k of keys) {
    if (JSON.stringify((a||{})[k]) !== JSON.stringify((b||{})[k]))
      parts.push(`${k}: <span class="diff-del">${JSON.stringify((a||{})[k])}</span> → <span class="diff-add">${JSON.stringify((b||{})[k])}</span>`);
  }
  return parts.join("<br>");
}

$("saveDef").onclick = () => guard(async () => {
  const r = await api("POST", "/api/definitions", JSON.parse($("defText").value));
  showMsg("定义已保存: " + JSON.stringify(r)); refresh();
});
$("createSession").onclick = () => guard(async () => {
  const r = await api("POST", "/api/branches", {
    definitionId: JSON.parse($("defText").value.id || "cell-gateway"),
    seed: Number($("seed").value), name: $("branchName").value,
    events: JSON.parse($("eventsText").value) });
  currentBranch = r.id; showMsg("会话已创建: " + r.id); refresh();
});
$("appendEvents").onclick = () => guard(async () => {
  await api("POST", `/api/branches/${currentBranch}/events`, { events: JSON.parse($("eventsText").value) });
  showMsg("事件已追加"); refresh();
});
$("stepBtn").onclick = () => guard(async () => {
  const r = await api("POST", `/api/branches/${currentBranch}/step`);
  showMsg(r.entry ? ("已执行步 #" + r.entry.step) : "队列为空"); refresh();
});
$("runBtn").onclick = () => guard(async () => {
  const r = await api("POST", `/api/branches/${currentBranch}/run`, { maxSteps: 10000 });
  showMsg("已播放 " + r.steps + " 步"); refresh();
});
$("cpBtn").onclick = () => guard(async () => {
  const name = prompt("检查点名称", "cp");
  if (name === null) return;
  await api("POST", `/api/branches/${currentBranch}/checkpoints`, { name });
  showMsg("检查点已建立"); refresh();
});
$("mergeBtn").onclick = () => guard(async () => {
  const r = await api("POST", "/api/merge", { into: currentBranch, from: $("mergeFrom").value });
  currentBranch = r.id; showMsg("合并成功，新分支: " + r.id); refresh();
});
$("diffBtn").onclick = () => guard(async () => {
  const d = await api("GET", `/api/branches/${currentBranch}/diff/${$("diffOther").value}`);
  $("diffOut").textContent = JSON.stringify(d, null, 2);
});
$("exportBtn").onclick = () => guard(async () => {
  const d = await api("GET", `/api/branches/${currentBranch}/export`);
  $("exportText").value = JSON.stringify(d);
  showMsg("已导出到文本框");
});
$("importBtn").onclick = () => guard(async () => {
  const r = await api("POST", "/api/import", JSON.parse($("exportText").value));
  currentBranch = r.id; showMsg("已导入: " + r.id + " 轨迹哈希=" + r.trajectoryHash); refresh();
});
$("refreshBtn").onclick = () => guard(refresh);
guard(refresh);
</script>
</body>
</html>
""";
}
