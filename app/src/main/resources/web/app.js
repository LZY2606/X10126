'use strict';

const $ = (id) => document.getElementById(id);
let state = { sessions: {} };
let currentSessionId = null;
let currentBranchId = 'main';
let selectedCheckpointId = null;
let toastTimer = null;

const sampleDefinition = {
  states: ['idle', 'armed', 'blocked', 'done'],
  initialState: 'idle',
  variables: { retry: 0, lastSource: null, balance: 2 },
  transitions: [
    {
      from: 'idle', on: 'arm', to: 'armed',
      actions: [
        { type: 'set', var: 'lastSource', value: { path: 'event.source' } },
        { type: 'output', channel: 'audit', payload: { literal: { message: 'armed' } } }
      ]
    },
    {
      from: 'armed', on: 'tick', to: 'blocked',
      condition: { op: 'ge', path: 'event.payload.danger', value: { literal: 5 } },
      actions: [
        { type: 'output', channel: 'alarm', payload: { literal: { level: 'high' } } },
        { type: 'emit', eventType: 'internal-cleanup', payload: { literal: { reason: 'danger' } } },
        { type: 'set', var: 'balance', value: { literal: 0 } },
        { type: 'fail', code: 'GUARD_REJECTED', message: '高风险 tick 在提交前失败' }
      ]
    },
    {
      from: 'armed', on: 'tick', to: 'done',
      actions: [
        { type: 'random', var: 'lucky', min: 1, max: 99 },
        { type: 'set', var: 'retry', value: { literal: 1 } },
        { type: 'output', channel: 'result', payload: { literal: { ok: true } } },
        { type: 'emit', eventType: 'internal-followup', payload: { literal: { phase: 'after-commit' } } }
      ]
    },
    {
      from: 'armed', on: 'internal-cleanup', to: 'done',
      actions: [{ type: 'output', channel: 'internal', payload: { literal: { saw: 'cleanup' } } }]
    },
    {
      from: 'armed', on: 'internal-followup', to: 'done',
      actions: [{ type: 'set', var: 'lastSource', value: { literal: 'internal' } }]
    }
  ]
};

const sampleEvents = [
  { logicalTime: 10, type: 'arm', source: 'ops', sourcePriority: 10, originalSeq: 1, payload: { request: 'a' } },
  { logicalTime: 12, type: 'tick', source: 'sensor-a', sourcePriority: 1, originalSeq: 3, payload: { danger: 7 } },
  { logicalTime: 12, type: 'tick', source: 'sensor-a', sourcePriority: 1, originalSeq: 2, payload: { danger: 1 } },
  { logicalTime: 12, type: 'tick', source: 'sensor-b', sourcePriority: 2, originalSeq: 1, payload: { danger: 4 } },
  { logicalTime: 15, type: 'tick', source: 'ops', sourcePriority: 10, originalSeq: 2, payload: { danger: 0 } }
];

$('definition').value = JSON.stringify(sampleDefinition, null, 2);
$('events').value = JSON.stringify(sampleEvents, null, 2);

function toast(message, bad = false) {
  const node = $('toast');
  node.textContent = message;
  node.style.borderColor = bad ? 'var(--bad)' : 'var(--primary)';
  node.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => node.classList.remove('show'), 3200);
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    method: options.method || 'GET',
    headers: { 'Content-Type': 'application/json' },
    body: options.body ? JSON.stringify(options.body) : undefined
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : {};
  if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
  return data;
}

function parsedTextarea(id) {
  return JSON.parse($(id).value);
}

function sessions() {
  return Object.values(state.sessions || {}).sort((a, b) => a.createdAtLogical - b.createdAtLogical);
}

function currentSession() {
  return state.sessions[currentSessionId];
}

function currentBranch() {
  const session = currentSession();
  return session && session.branches[currentBranchId];
}

function branches() {
  const session = currentSession();
  return session ? Object.values(session.branches) : [];
}

function short(value) {
  return value ? String(value).slice(0, 16) : '-';
}

function renderSessions() {
  const list = sessions();
  if (!currentSessionId && list.length) currentSessionId = list[0].id;
  $('sessionSelect').innerHTML = list.map((session) =>
    `<option value="${session.id}" ${session.id === currentSessionId ? 'selected' : ''}>${escapeHtml(session.name)} · ${short(session.definitionFingerprint)}</option>`
  ).join('');
}

function renderBranchSelectors() {
  const list = branches();
  if (!list.some((branch) => branch.id === currentBranchId) && list.length) currentBranchId = list[0].id;
  const options = list.map((branch) =>
    `<option value="${branch.id}" ${branch.id === currentBranchId ? 'selected' : ''}>${escapeHtml(branch.name || branch.id)} @${absolute(branch)}</option>`
  ).join('');
  $('branchSelect').innerHTML = options;
  $('compareLeft').innerHTML = options;
  $('compareRight').innerHTML = options;
  if (list.length > 1) $('compareRight').value = list[1].id;
}

function absolute(branch) {
  return Number(branch.rootAbsoluteIndex || 0) + (branch.steps || []).length;
}

function renderHeader() {
  const session = currentSession();
  const branch = currentBranch();
  $('definitionFingerprint').textContent = session ? `定义：${session.definitionFingerprint}` : '定义：未创建';
  $('trajectoryHash').textContent = branch ? `轨迹：${branch.trajectoryHash}` : '轨迹：-';
}

function renderBranch() {
  const branch = currentBranch();
  if (!branch) {
    $('queue').textContent = '暂无会话';
    $('snapshot').textContent = '{}';
    $('steps').innerHTML = '';
    $('checkpoints').innerHTML = '';
    renderHeader();
    return;
  }
  $('queue').innerHTML = (branch.pending || []).map(renderEvent).join('') || '<p class="muted">队列为空</p>';
  $('snapshot').textContent = JSON.stringify(branch.snapshot, null, 2);
  $('steps').innerHTML = (branch.steps || []).map(renderStep).join('') || '<p class="muted">尚未执行任何步骤</p>';
  renderCheckpoints();
  renderHeader();
}

function renderEvent(event) {
  const internal = Boolean(event.internal);
  return `<article class="eventCard">
    <div class="eventTitle"><span>${internal ? '内部' : '外部'} · ${escapeHtml(event.type)}</span><span class="mono">t=${event.logicalTime}</span></div>
    <div class="mono muted">prio=${event.sourcePriority} seq=${event.originalSeq} source=${escapeHtml(event.source)} ${internal ? `parent=${event.parentStep}` : ''}</div>
    <pre>${escapeHtml(JSON.stringify(event.payload, null, 2))}</pre>
  </article>`;
}

function renderStep(step) {
  const failed = step.outcome === 'action_failed';
  const diff = stepDiff(step);
  return `<article class="stepCard">
    <div class="stepTitle"><span>#${step.index} ${escapeHtml(step.event.type)}</span><span class="${failed ? 'bad' : 'good'}">${step.outcome}</span></div>
    <div class="mono muted">${escapeHtml(step.event.source)} · t=${step.event.logicalTime} · ${step.stateBefore} → ${step.stateAfter}</div>
    <h4>前后差异</h4>
    <pre>${escapeHtml(JSON.stringify(diff, null, 2))}</pre>
    <h4>输出</h4>
    <pre>${escapeHtml(JSON.stringify(step.outputs || [], null, 2))}</pre>
    ${step.failure ? `<h4 class="bad">失败记录（状态与内部事件已回滚）</h4><pre>${escapeHtml(JSON.stringify(step.failure, null, 2))}</pre>` : ''}
    ${(step.internalEvents || []).length ? `<h4>派生内部事件</h4><pre>${escapeHtml(JSON.stringify(step.internalEvents, null, 2))}</pre>` : ''}
    <div class="mono muted">hash ${step.hash}</div>
  </article>`;
}

function stepDiff(step) {
  const result = { state: { before: step.before.state, after: step.after.state }, variables: {} };
  const before = step.before.variables || {};
  const after = step.after.variables || {};
  for (const key of new Set([...Object.keys(before), ...Object.keys(after)])) {
    if (JSON.stringify(before[key]) !== JSON.stringify(after[key])) {
      result.variables[key] = { before: before[key] ?? null, after: after[key] ?? null };
    }
  }
  result.rngState = { before: step.before.rngState, after: step.after.rngState };
  return result;
}

function renderCheckpoints() {
  const session = currentSession();
  const checkpoints = Object.values(session.checkpoints || {})
    .filter((checkpoint) => checkpoint.branchId === currentBranchId || branches().some((branch) => branch.rootCheckpointId === checkpoint.id))
    .sort((a, b) => b.absoluteIndex - a.absoluteIndex);
  $('checkpoints').innerHTML = checkpoints.map((checkpoint) => {
    const valid = checkpoint.definitionFingerprint === session.definitionFingerprint;
    return `<article class="checkpointCard ${checkpoint.id === selectedCheckpointId ? 'selected' : ''}" data-id="${checkpoint.id}">
      <div class="eventTitle"><strong>${escapeHtml(checkpoint.name)}</strong><span class="mono">@${checkpoint.absoluteIndex}</span></div>
      <div class="mono muted">${escapeHtml(checkpoint.branchId)} · ${valid ? '<span class="good">定义匹配</span>' : '<span class="bad">定义拒绝</span>'}</div>
      <div class="mono muted">${checkpoint.trajectoryHash}</div>
    </article>`;
  }).join('') || '<p class="muted">暂无检查点</p>';
  document.querySelectorAll('.checkpointCard').forEach((card) => {
    card.addEventListener('click', () => selectedCheckpointId = card.dataset.id);
  });
}

async function refresh() {
  state = await api('/api/state');
  renderSessions();
  renderBranchSelectors();
  renderBranch();
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

$('validateDefinition').addEventListener('click', async () => {
  try {
    const result = await api('/api/validate', { method: 'POST', body: { definition: parsedTextarea('definition') } });
    toast(`定义有效，指纹 ${result.fingerprint.slice(0, 16)}…`);
  } catch (error) {
    toast(error.message, true);
  }
});

$('createSession').addEventListener('click', async () => {
  try {
    const session = await api('/api/sessions', {
      method: 'POST',
      body: {
        name: $('sessionName').value,
        seed: Number($('seed').value),
        definition: parsedTextarea('definition')
      }
    });
    currentSessionId = session.id;
    currentBranchId = 'main';
    selectedCheckpointId = null;
    await refresh();
    toast('会话已创建：定义、初始状态与种子已锁定');
  } catch (error) {
    toast(error.message, true);
  }
});

$('sessionSelect').addEventListener('change', async () => {
  currentSessionId = $('sessionSelect').value;
  currentBranchId = 'main';
  selectedCheckpointId = null;
  await refresh();
});

$('branchSelect').addEventListener('change', () => {
  currentBranchId = $('branchSelect').value;
  renderBranch();
});

$('loadSampleEvents').addEventListener('click', () => {
  $('events').value = JSON.stringify(sampleEvents, null, 2);
});

$('importEvents').addEventListener('click', async () => {
  try {
    await api(`/api/sessions/${currentSessionId}/events`, {
      method: 'POST',
      body: { branchId: currentBranchId, events: parsedTextarea('events') }
    });
    await refresh();
    toast('事件已按逻辑时间、来源优先级和原始序号入队');
  } catch (error) {
    toast(error.message, true);
  }
});

$('stepButton').addEventListener('click', async () => {
  try {
    await api(`/api/sessions/${currentSessionId}/step`, {
      method: 'POST',
      body: { branchId: currentBranchId }
    });
    await refresh();
  } catch (error) {
    toast(error.message, true);
  }
});

$('checkpointButton').addEventListener('click', async () => {
  try {
    const checkpoint = await api(`/api/sessions/${currentSessionId}/checkpoints`, {
      method: 'POST',
      body: { branchId: currentBranchId, name: `检查点 ${absolute(currentBranch())}` }
    });
    selectedCheckpointId = checkpoint.id;
    await refresh();
    toast('检查点已保存并绑定定义指纹');
  } catch (error) {
    toast(error.message, true);
  }
});

$('forkButton').addEventListener('click', async () => {
  if (!selectedCheckpointId) return toast('请先选择一个检查点', true);
  try {
    const branch = await api(`/api/sessions/${currentSessionId}/fork`, {
      method: 'POST',
      body: { checkpointId: selectedCheckpointId, name: `分叉-${Date.now().toString(36)}` }
    });
    currentBranchId = branch.id;
    await refresh();
    toast('已从检查点分叉');
  } catch (error) {
    toast(error.message, true);
  }
});

$('restoreButton').addEventListener('click', async () => {
  if (!selectedCheckpointId) return toast('请先选择一个检查点', true);
  try {
    await api(`/api/sessions/${currentSessionId}/restore`, {
      method: 'POST',
      body: { checkpointId: selectedCheckpointId }
    });
    await refresh();
    toast('分支已恢复到检查点');
  } catch (error) {
    toast(error.message, true);
  }
});

$('compareButton').addEventListener('click', async () => {
  try {
    const result = await api(`/api/sessions/${currentSessionId}/compare?left=${encodeURIComponent($('compareLeft').value)}&right=${encodeURIComponent($('compareRight').value)}`);
    $('comparison').textContent = JSON.stringify({
      sameTrajectoryHash: result.sameTrajectoryHash,
      outputCount: result.outputCount,
      failureCount: result.failureCount,
      snapshotDiff: result.snapshotDiff
    }, null, 2);
  } catch (error) {
    toast(error.message, true);
  }
});

$('mergeButton').addEventListener('click', async () => {
  try {
    const result = await api(`/api/sessions/${currentSessionId}/merge`, {
      method: 'POST',
      body: {
        leftId: $('compareLeft').value,
        rightId: $('compareRight').value,
        name: $('mergeName').value,
        create: $('mergeCreate').checked
      }
    });
    $('mergeResult').textContent = JSON.stringify(result, null, 2);
    await refresh();
    toast(result.allowed ? '外部事件集合兼容，合并已确定性重放' : `合并拒绝：${result.firstConflict.type}`);
  } catch (error) {
    toast(error.message, true);
  }
});

$('exportSession').addEventListener('click', async () => {
  try {
    const exported = await api(`/api/sessions/${currentSessionId}/export`);
    $('exportText').value = JSON.stringify(exported, null, 2);
    $('exportDialog').showModal();
  } catch (error) {
    toast(error.message, true);
  }
});

$('closeExport').addEventListener('click', () => $('exportDialog').close());
$('copyExport').addEventListener('click', async () => {
  await navigator.clipboard.writeText($('exportText').value);
  toast('导出内容已复制');
});

$('importSessionFile').addEventListener('change', async (event) => {
  const file = event.target.files[0];
  if (!file) return;
  try {
    const payload = JSON.parse(await file.text());
    const session = await api('/api/sessions/import', { method: 'POST', body: payload });
    currentSessionId = session.id;
    currentBranchId = 'main';
    selectedCheckpointId = null;
    await refresh();
    toast('会话导入成功，轨迹哈希已校验');
  } catch (error) {
    toast(error.message, true);
  } finally {
    event.target.value = '';
  }
});

$('refreshButton').addEventListener('click', refresh);
refresh().catch((error) => toast(error.message, true));
