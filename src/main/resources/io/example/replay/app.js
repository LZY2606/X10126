const $ = (id) => document.getElementById(id);
let definition = null;
let session = null;

function pretty(value) {
  return JSON.stringify(value, null, 2);
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options
  });
  const text = await response.text();
  const body = text ? JSON.parse(text) : {};
  if (!response.ok) {
    throw new Error(body.error || response.statusText);
  }
  return body;
}

function message(value) {
  $('message').textContent = typeof value === 'string' ? value : pretty(value);
}

async function loadDefinition() {
  definition = await api('/api/definition');
  $('definition').value = pretty(definition);
  $('definitionFingerprint').textContent = definition.fingerprint || '未保存';
}

async function saveDefinition() {
  const parsed = JSON.parse($('definition').value);
  definition = await api('/api/definition', { method: 'PUT', body: JSON.stringify(parsed) });
  $('definitionFingerprint').textContent = definition.fingerprint;
  $('definitionStatus').textContent = '已保存：' + new Date().toLocaleTimeString();
}

async function createSession() {
  const body = {
    id: $('sessionId').value || undefined,
    seed: Number($('seed').value || 0),
    events: JSON.parse($('events').value),
    name: '回放会话'
  };
  session = await api('/api/sessions', { method: 'POST', body: JSON.stringify(body) });
  $('sessionStatus').textContent = '会话指纹 ' + session.inputFingerprint;
  renderSession();
}

async function loadSession() {
  const id = $('sessionId').value;
  session = await api('/api/sessions/' + encodeURIComponent(id));
  renderSession();
}

function currentBranch() {
  return session.branches.find((branch) => branch.id === $('branch').value) || session.branches[0];
}

function renderSession() {
  if (!session) return;
  const selected = $('branch').value;
  $('branch').innerHTML = session.branches.map((branch) =>
    `<option value="${branch.id}">${branch.name} (${branch.id})</option>`).join('');
  if (session.branches.some((branch) => branch.id === selected)) {
    $('branch').value = selected;
  }
  const branch = currentBranch();
  $('definitionFingerprint').textContent = branch.definitionFingerprint;
  $('currentState').textContent = pretty({
    current: branch.current,
    traceHash: branch.traceHash,
    inputFingerprint: branch.inputFingerprint,
    steps: branch.steps
  });
  $('queues').textContent = pretty({
    pendingExternal: branch.pendingExternal,
    internalQueue: branch.internalQueue
  });
  $('checkpointSelect').innerHTML = branch.checkpoints.map((checkpoint) =>
    `<option value="${checkpoint.id}">${checkpoint.label || checkpoint.id} @ ${checkpoint.stepIndex}</option>`).join('');
  $('trace').textContent = pretty(branch.trace.map((step) => ({
    index: step.index,
    event: `${step.source}/${step.eventType}/${step.eventId}@${step.logicalTime}`,
    status: step.status,
    error: step.error,
    outputs: step.outputs,
    traceHash: step.traceHash
  })));
}

async function step() {
  const branch = currentBranch();
  const result = await api(`/api/sessions/${session.id}/branches/${branch.id}?action=step`, { method: 'POST' });
  await loadSession();
  $('diff').innerHTML = result.diff.map((change) =>
    `<div class="change"><div class="path">${change.path}</div><div class="before">${JSON.stringify(change.before)}</div><div class="after">${JSON.stringify(change.after)}</div>`).join('');
  message({
    event: result.event,
    before: result.before,
    after: result.after,
    diff: result.diff
  });
}

async function checkpoint() {
  const branch = currentBranch();
  const label = `cp-${branch.steps}`;
  await api(`/api/sessions/${session.id}/branches/${branch.id}?action=checkpoint`, {
    method: 'POST',
    body: JSON.stringify({ label })
  });
  await loadSession();
  message('已建立检查点 ' + label);
}

async function restore() {
  const branch = currentBranch();
  await api(`/api/sessions/${session.id}/branches/${branch.id}/restore`, {
    method: 'POST',
    body: JSON.stringify({ checkpointId: $('checkpointSelect').value })
  });
  await loadSession();
  message('已恢复检查点');
}

async function fork() {
  const branch = currentBranch();
  await api(`/api/sessions/${session.id}/forks`, {
    method: 'POST',
    body: JSON.stringify({
      sourceBranchId: branch.id,
      checkpointId: $('checkpointSelect').value,
      branchId: `fork-${Date.now()}`,
      name: `分叉 @ ${$('checkpointSelect').value}`
    })
  });
  await loadSession();
  message('分叉已创建');
}

async function merge() {
  const branch = currentBranch();
  const result = await api(`/api/sessions/${session.id}/merges`, {
    method: 'POST',
    body: JSON.stringify({
      leftBranchId: 'main',
      rightBranchId: branch.id,
      mergedBranchId: `merged-${Date.now()}`
    })
  });
  $('compareResult').textContent = pretty(result);
  await loadSession();
}

async function compare() {
  const branch = currentBranch();
  const result = await api(`/api/sessions/${session.id}/compare/${branch.id}?with=main`);
  $('compareResult').textContent = pretty(result);
}

async function exportSession() {
  const response = await fetch(`/api/sessions/${session.id}/export`);
  const blob = await response.blob();
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = `${session.id}-export.json`;
  link.click();
  URL.revokeObjectURL(link.href);
}

async function importSession(file) {
  const text = await file.text();
  const response = await fetch('/api/sessions/import', { method: 'POST', body: text });
  if (!response.ok) throw new Error(await response.text());
  const imported = await response.json();
  $('sessionId').value = imported.id;
  await loadSession();
  message('导入完成，轨迹哈希已在服务端重放验证');
}

$('saveDefinition').onclick = () => saveDefinition().catch((e) => message(e.message));
$('createSession').onclick = () => createSession().catch((e) => message(e.message));
$('loadSession').onclick = () => loadSession().catch((e) => message(e.message));
$('step').onclick = () => step().catch((e) => message(e.message));
$('checkpoint').onclick = () => checkpoint().catch((e) => message(e.message));
$('restore').onclick = () => restore().catch((e) => message(e.message));
$('fork').onclick = () => fork().catch((e) => message(e.message));
$('merge').onclick = () => merge().catch((e) => message(e.message));
$('compare').onclick = () => compare().catch((e) => message(e.message));
$('exportSession').onclick = () => exportSession().catch((e) => message(e.message));
$('importFile').onchange = (event) => importSession(event.target.files[0]).catch((e) => message(e.message));
$('branch').onchange = renderSession;

(async function init() {
  try {
    await loadDefinition();
    $('events').value = pretty([
      { id: 'e-low', type: 'start', time: 10, source: 'api', priority: 20, originalSeq: 2, payload: { priority: 1 } },
      { id: 'e-high', type: 'start', time: 10, source: 'ops', priority: 5, originalSeq: 1, payload: { priority: 9 } },
      { id: 'e-bad', type: 'bad', time: 11, source: 'ops', priority: 1, originalSeq: 1, payload: {} },
      { id: 'e-finish', type: 'finish', time: 12, source: 'api', priority: 1, originalSeq: 1, payload: {} }
    ]);
  } catch (error) {
    message(error.message);
  }
})();
