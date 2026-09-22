const $ = (id) => document.getElementById(id);
let project = null;
let currentBranchId = 'main';

const sampleEvents = [
  { id: 'sensor-1-1', logicalTime: 1, source: 'sensor', originalSeq: 1, event: 'tick', data: {} },
  { id: 'operator-1-1', logicalTime: 1, source: 'operator', originalSeq: 1, event: 'start', data: {} },
  { id: 'sensor-2-1', logicalTime: 2, source: 'sensor', originalSeq: 1, event: 'tick', data: {} },
  { id: 'sensor-3-1', logicalTime: 3, source: 'sensor', originalSeq: 1, event: 'tick', data: {} },
  { id: 'timer-4-1', logicalTime: 4, source: 'timer', originalSeq: 1, event: 'panic', data: { hard: true } }
];

async function api(path, options = {}) {
  const response = await fetch(path, {
    method: options.method || 'GET',
    headers: options.body ? { 'Content-Type': 'application/json' } : {},
    body: options.body ? JSON.stringify(options.body) : undefined
  });
  const data = await response.json();
  if (!response.ok || data.ok === false) {
    throw new Error(data.error || `HTTP ${response.status}`);
  }
  return data;
}

function qs(params) {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') query.set(key, value);
  });
  const text = query.toString();
  return text ? `?${text}` : '';
}

function parsedJson(textarea, name) {
  try {
    return JSON.parse(textarea.value);
  } catch (error) {
    throw new Error(`${name} 不是有效 JSON: ${error.message}`);
  }
}

function showError(error) {
  const toast = $('toast');
  toast.textContent = error.stack ? error.message : String(error);
  toast.className = 'toast error';
  setTimeout(() => toast.classList.add('hidden'), 6500);
}

function short(value) {
  return String(value || '').slice(0, 16);
}

async function loadSample() {
  $('definition').value = JSON.stringify(await api('/api/sample'), null, 2);
  $('events').value = JSON.stringify(sampleEvents, null, 2);
}

async function createProject() {
  const definition = parsedJson($('definition'), '状态机定义');
  const events = parsedJson($('events'), '事件日志');
  project = await api('/api/projects', {
    method: 'POST',
    body: { id: $('projectId').value.trim(), definition, events, seedLogId: 'initial-log' }
  });
  currentBranchId = 'main';
  toast('项目已创建，定义指纹：' + project.definitionFingerprint.slice(0, 16));
  render();
}

async function loadProject() {
  project = await api('/api/projects' + qs({ projectId: $('projectId').value.trim(), branchId: currentBranchId }));
  render();
}

async function appendEvents() {
  const events = parsedJson($('events'), '事件日志');
  project = await api('/api/events/append' + qs({ projectId: project.id, branchId: currentBranchId }), {
    method: 'POST', body: { events }
  });
  render();
}

async function step(count) {
  project = await api('/api/replay/step' + qs({ projectId: project.id, branchId: currentBranchId }), {
    method: 'POST', body: { count }
  });
  render();
}

async function checkpoint() {
  project = await api('/api/checkpoints' + qs({ projectId: project.id, branchId: currentBranchId }), {
    method: 'POST', body: { name: $('checkpointName').value.trim() || ('cp-' + Date.now()) }
  });
  render();
}

async function fork() {
  project = await api('/api/branches/fork' + qs({ projectId: project.id, sourceBranchId: currentBranchId }), {
    method: 'POST',
    body: {
      checkpointId: $('forkCheckpoint').value.trim(),
      branchId: $('forkBranchId').value.trim(),
      name: $('forkBranchId').value.trim()
    }
  });
  currentBranchId = $('forkBranchId').value.trim();
  render();
}

async function merge() {
  const result = await api('/api/branches/merge' + qs({
    projectId: project.id,
    targetBranchId: currentBranchId,
    sourceBranchId: $('mergeSourceBranch').value.trim()
  }), {
    method: 'POST',
    body: { branchId: $('mergeBranchId').value.trim(), name: $('mergeBranchId').value.trim() }
  });
  if (result.ok === false) {
    $('branchResult').textContent = '合并被拒绝\n' + JSON.stringify(result.conflict, null, 2);
  } else {
    project = result;
    currentBranchId = $('mergeBranchId').value.trim();
    render();
  }
}

async function compare() {
  const result = await api('/api/branches/compare' + qs({
    projectId: project.id, leftBranchId: currentBranchId, rightBranchId: $('compareBranch').value
  }));
  $('branchResult').textContent = JSON.stringify(result, null, 2);
}

async function exportSession() {
  const session = await api('/api/sessions/export' + qs({ projectId: project.id }));
  $('sessionBox').value = JSON.stringify(session, null, 2);
  toast('会话指纹：' + session.sessionFingerprint.slice(0, 16));
}

async function importSession() {
  const session = parsedJson($('sessionBox'), '会话');
  project = await api('/api/sessions/import', { method: 'POST', body: session });
  currentBranchId = 'main';
  toast('导入重放成功，轨迹哈希一致');
  render();
}

function toast(message) {
  const node = $('toast');
  node.textContent = message;
  node.className = 'toast';
  setTimeout(() => node.classList.add('hidden'), 4000);
}

function currentBranch() {
  return project.branches.find((branch) => branch.id === currentBranchId) || project.currentBranch;
}

function render() {
  if (!project) return;
  const branch = currentBranch();
  $('stateView').textContent = JSON.stringify({
    branch: branch.id,
    definitionFingerprint: project.definitionFingerprint,
    checkpoint: branch.currentCheckpointId,
    state: branch.state
  }, null, 2);
  $('branchSelect').innerHTML = project.branches.map((item) =>
    `<option value="${item.id}" ${item.id === branch.id ? 'selected' : ''}>${item.name}</option>`).join('');
  $('compareBranch').innerHTML = project.branches.filter((item) => item.id !== branch.id)
    .map((item) => `<option value="${item.id}">${item.name}</option>`).join('');
  renderSteps(branch.steps || []);
  renderCheckpoints(branch.checkpoints || []);
}

function renderSteps(steps) {
  $('steps').innerHTML = steps.map((step) => {
    const event = step.event;
    const title = `${step.stepIndex}. t=${event.logicalTime} ${event.source}/${event.originalSeq} ${event.event} ${event.kind}`;
    const detail = {
      before: step.before,
      after: step.after,
      outputs: step.outputs,
      derivedInternalEvents: step.derivedInternalEvents.map((item) => `${item.source}/${item.event}`),
      failure: step.failure,
      fingerprint: step.fingerprint
    };
    return `<article class="step ${step.failure ? 'failed' : ''}">
      <div class="step-title"><span>${title}</span><span class="badge ${step.failure ? 'failure' : ''}">${step.failure ? '失败已回滚' : (step.transition || '无转换')}</span></div>
      <pre>${escapeHtml(JSON.stringify(detail, null, 2))}</pre>
    </article>`;
  }).reverse().join('');
}

function renderCheckpoints(checkpoints) {
  $('checkpoints').innerHTML = checkpoints.map((cp) => `<div class="checkpoint">
    <strong>${escapeHtml(cp.name)}</strong> #${cp.stepIndex} 状态=${cp.currentState}<br>
    <code>${cp.checkpointId}</code><br>
    <span>定义：${short(cp.definitionFingerprint)} 轨迹：${short(cp.trajectoryHash)}</span>
  </div>`).join('');
}

function escapeHtml(value) {
  return value.replace(/[&<>"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[char]));
}

function bind() {
  $('loadSample').onclick = () => loadSample().catch(showError);
  $('validateDefinition').onclick = () => {
    parsedJson($('definition'), '状态机定义');
    parsedJson($('events'), '事件日志');
    toast('JSON 语法有效');
  };
  $('newProject').onclick = () => createProject().catch(showError);
  $('loadProject').onclick = () => loadProject().catch(showError);
  $('appendEvents').onclick = () => appendEvents().catch(showError);
  $('stepOne').onclick = () => step(1).catch(showError);
  $('runAll').onclick = () => step(0).catch(showError);
  $('checkpoint').onclick = () => checkpoint().catch(showError);
  $('fork').onclick = () => fork().catch(showError);
  $('merge').onclick = () => merge().catch(showError);
  $('compare').onclick = () => compare().catch(showError);
  $('exportSession').onclick = () => exportSession().catch(showError);
  $('importSession').onclick = () => importSession().catch(showError);
  $('branchSelect').onchange = (event) => {
    currentBranchId = event.target.value;
    loadProject().catch(showError);
  };
}

bind();
loadSample().catch(showError);
