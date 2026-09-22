'use strict';

let session = null;
let activeBranch = 'main';
let lastDiff = null;

const $ = (id) => document.getElementById(id);

async function api(path, body) {
  const opt = { method: body ? 'POST' : 'GET', headers: {} };
  if (body) {
    opt.headers['Content-Type'] = 'application/json';
    opt.body = JSON.stringify(body);
  }
  const res = await fetch(path, opt);
  const data = await res.json().catch(() => ({ error: 'invalid json response' }));
  if (!res.ok) {
    const msg = data.error + (data.detail ? '\n' + JSON.stringify(data.detail, null, 2) : '');
    throw new Error(msg);
  }
  if (data.session) {
    session = data.session;
  }
  return data;
}

function toast(msg, isErr) {
  const t = $('toast');
  t.textContent = msg;
  t.className = 'toast' + (isErr ? ' err' : '');
  t.style.display = 'block';
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => { t.style.display = 'none'; }, isErr ? 9000 : 3500);
}

function short(fp, n = 14) {
  return fp ? fp.slice(0, n) + '…' : '';
}

function esc(s) {
  return String(s ?? '').replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
}

function branches() {
  return Object.entries(session.branches || {});
}

function branch(id) {
  return session.branches[id];
}

function trace(id) {
  return session.traces[id] || [];
}

function render() {
  if (!session) return;
  $('defVer').textContent = 'v' + (session.definition.version || '?');
  $('defFp').textContent = '指纹 ' + session.definitionFingerprint;
  $('activeBranchName').textContent = activeBranch;

  // 下拉
  const ids = branches().map(([id]) => id);
  for (const [sel, val] of [['importBranch', activeBranch], ['cmpA', activeBranch], ['cmpB', 'main']]) {
    const cur = $(sel).value;
    $(sel).innerHTML = ids.map((id) => `<option value="${esc(id)}">${esc(id)}</option>`).join('');
    $(sel).value = ids.includes(cur) ? cur : (ids.includes(val) ? val : ids[0]);
  }
  // 检查点下拉
  $('forkCp').innerHTML = (session.checkpoints || []).map((cp) =>
    `<option value="${esc(cp.id)}">${esc(cp.id)} @${esc(cp.branch)}#${cp.step} — ${esc(cp.label || '')}${cp.compatible ? '' : '（定义不兼容）'}</option>`
  ).join('');

  renderBranch();
  renderCheckpoints();
  renderImportLog();
}

function renderBranch() {
  const b = branch(activeBranch);
  $('curState').textContent = b.currentState;
  $('stepCount').textContent = b.stepCount;
  $('traceHash').textContent = short(b.traceHash, 22);
  $('pendingExt').textContent = b.pendingExternalCount;
  $('pendingInt').textContent = b.pendingInternalCount;
  $('stateView').textContent = JSON.stringify(b.state, null, 2);
  if (!lastDiff) {
    $('diffView').textContent = '（单步后显示本步差异与输出）';
  } else {
    renderDiff();
  }

  const tr = trace(activeBranch);
  const tbody = $('traceTable').querySelector('tbody');
  tbody.innerHTML = tr.map((e) => {
    const ev = e.event || {};
    const intChip = ev.internal ? '<span class="chip internal">内部</span> ' : '';
    let detail = '';
    if (e.status === 'failed') {
      detail = `<span class="chip failed">失败回滚</span> ${esc(e.failure || '')}`;
    } else if (e.outputs && e.outputs.length) {
      detail = e.outputs.map((o) => `<span class="chip ok">${esc(o.channel)}</span> ${esc(JSON.stringify(o.value))}`).join('<br>');
    }
    return `<tr>
      <td class="mono">${e.seq}</td>
      <td>${intChip}${esc(ev.type)}<div class="muted mono">${esc(ev.id)}</div></td>
      <td class="mono">${ev.time ?? ''}</td>
      <td class="mono">${esc(e.from)}→${esc(e.to)}</td>
      <td class="mono">${e.transition ?? '—'}</td>
      <td>${detail || '<span class="muted">—</span>'}</td>
    </tr>`;
  }).join('');

  renderBranchCards();
}

function renderDiff() {
  const d = lastDiff;
  const lines = [];
  for (const x of d.added || []) lines.push(`+ ${x.path} = ${JSON.stringify(x.after)}`);
  for (const x of d.removed || []) lines.push(`- ${x.path} = ${JSON.stringify(x.before)}`);
  for (const x of d.changed || []) lines.push(`~ ${x.path}: ${JSON.stringify(x.before)} -> ${JSON.stringify(x.after)}`);
  const entries = d.entries || [];
  for (const e of entries) {
    if (e.status === 'failed') lines.push(`! 失败已记录但状态回滚: ${e.failure}`);
    for (const o of (e.outputs || [])) lines.push(`> [${o.channel}] ${JSON.stringify(o.value)}`);
  }
  $('diffView').innerHTML = lines.length
    ? lines.map((l) => {
        const cls = l[0] === '+' ? 'diff-add' : l[0] === '-' ? 'diff-del' : l[0] === '~' ? 'diff-chg' : '';
        return `<div class="${cls}">${esc(l)}</div>`;
      }).join('')
    : '（本步无状态变化）';
}

function renderBranchCards() {
  $('branchList').innerHTML = branches().map(([id, b]) => {
    const active = id === activeBranch ? ' active' : '';
    return `<div class="branch-card${active}">
      <div style="display:flex;justify-content:space-between;gap:6px">
        <strong>${esc(id)}</strong>
        <span class="mono muted">${esc(b.currentState)} #${b.stepCount}</span>
      </div>
      <div class="muted mono" style="font-size:10px;margin:4px 0">${short(b.traceHash, 18)}</div>
      <div class="muted" style="font-size:11px">
        ${b.parentCheckpoint ? `自 ${esc(b.parentCheckpoint)} 分叉` : '主干'} ·
        待处理 ${b.pendingExternalCount}/${b.pendingInternalCount}
      </div>
      ${id === activeBranch ? '' : `<div class="row" style="margin-top:6px"><button class="ghost" onclick="switchBranch('${esc(id)}')">切换查看</button></div>`}
    </div>`;
  }).join('');
}

function renderCheckpoints() {
  $('cpList').innerHTML = (session.checkpoints || []).map((cp) => `
    <div class="branch-card" style="padding:6px 8px">
      <div style="display:flex;justify-content:space-between">
        <strong>${esc(cp.label || cp.id)}</strong>
        <span class="chip ${cp.compatible ? 'ok' : 'failed'}">${cp.compatible ? '指纹一致' : '定义不兼容'}</span>
      </div>
      <div class="muted mono" style="font-size:10px;margin-top:3px">
        ${esc(cp.id)} @${esc(cp.branch)}#${cp.step} · 定义 v${esc(cp.definitionVersion)}
      </div>
      <div class="muted mono hash" style="font-size:10px">${short(cp.traceHash, 20)}</div>
    </div>`).join('');
}

function renderImportLog() {
  const rows = (session.importLog || []).slice().sort((a, b) =>
    a.time - b.time || b.priority - a.priority || a.seq - b.seq);
  $('importTable').innerHTML =
    '<thead><tr><th>t</th><th>优先级</th><th>序号</th><th>类型</th><th>来源</th></tr></thead>' +
    '<tbody>' + rows.map((e) => `<tr>
      <td class="mono">${e.time}</td><td class="mono">${e.priority}</td>
      <td class="mono">${e.seq}</td><td>${esc(e.type)}</td>
      <td class="muted">${esc(e.source)}</td></tr>`).join('') + '</tbody>';
}

window.switchBranch = (id) => {
  activeBranch = id;
  lastDiff = null;
  render();
};

// ---------------- 事件绑定 ----------------

$('btnSaveDef').onclick = async () => {
  try {
    const def = JSON.parse($('defEditor').value);
    const data = await api('/api/definition', { definition: def });
    session = data.session;
    activeBranch = 'main';
    lastDiff = null;
    render();
    toast('定义已保存为新版本（版本/初始状态/种子已锁定）');
  } catch (e) {
    toast(e.message, true);
  }
};

$('btnFormatDef').onclick = () => {
  try {
    $('defEditor').value = JSON.stringify(JSON.parse($('defEditor').value), null, 2);
  } catch (e) {
    toast(e.message, true);
  }
};

$('btnImport').onclick = async () => {
  try {
    const events = JSON.parse($('eventEditor').value);
    if (!Array.isArray(events)) throw new Error('事件必须是 JSON 数组');
    const data = await api('/api/events/import', { branch: $('importBranch').value, events });
    session = data.session;
    render();
    toast(`已导入 ${data.imported} 个事件（同时刻按 优先级↓ 序号↑ 排序）`);
  } catch (e) {
    toast(e.message, true);
  }
};

$('btnStep').onclick = async () => {
  try {
    const data = await api('/api/step', { branch: activeBranch, maxSteps: 1 });
    session = data.session;
    if (!data.entries.length) {
      toast('没有可处理的事件');
      return;
    }
    lastDiff = { entries: data.entries, ...data.stateDiff };
    render();
  } catch (e) {
    toast(e.message, true);
  }
};

$('btnRun').onclick = async () => {
  try {
    const data = await api('/api/run', { branch: activeBranch, maxSteps: 0 });
    session = data.session;
    render();
    toast(`播放完成，执行 ${data.steps} 步`);
  } catch (e) {
    toast(e.message, true);
  }
};

$('btnCp').onclick = async () => {
  try {
    const data = await api('/api/checkpoints', { branch: activeBranch, label: $('cpLabel').value });
    session = data.session;
    $('cpLabel').value = '';
    render();
    toast(`检查点 ${data.checkpoint.id} 已建立，定义指纹已锁定`);
  } catch (e) {
    toast(e.message, true);
  }
};

$('btnFork').onclick = async () => {
  try {
    const data = await api('/api/branches/fork', {
      checkpoint: $('forkCp').value,
      name: $('forkName').value || null,
      playPending: $('forkPending').checked
    });
    session = data.session;
    activeBranch = data.branch.id;
    $('forkName').value = '';
    lastDiff = null;
    render();
    toast(`已从检查点分叉出新分支 ${data.branch.id}`);
  } catch (e) {
    toast('分叉被拒绝：\n' + e.message, true);
  }
};

$('btnCompare').onclick = async () => {
  try {
    const d = await api('/api/branches/compare', { a: $('cmpA').value, b: $('cmpB').value });
    const lines = [`轨迹哈希一致: ${d.sameTraceHash ? '是' : '否'}`];
    for (const x of d.stateDiff.added) lines.push(`+ ${x.path} = ${JSON.stringify(x.after)}`);
    for (const x of d.stateDiff.removed) lines.push(`- ${x.path} = ${JSON.stringify(x.before)}`);
    for (const x of d.stateDiff.changed) lines.push(`~ ${x.path}: ${JSON.stringify(x.before)} -> ${JSON.stringify(x.after)}`);
    showMerge(lines.join('\n') || '状态与输出完全一致');
  } catch (e) {
    toast(e.message, true);
  }
};

$('btnMergePlan').onclick = async () => {
  try {
    const plan = await api('/api/merge/plan', { a: $('cmpA').value, b: $('cmpB').value });
    showMerge(`可以合并：共同祖先 ${plan.ancestor}@#${plan.ancestorStep}\n` +
      `规范顺序事件 ${plan.events.length} 个，末端结果${plan.identicalResults ? '一致' : '不同（将重放验证）'}`);
  } catch (e) {
    showMerge('合并被拒绝：\n' + e.message, true);
  }
};

$('btnMerge').onclick = async () => {
  try {
    const res = await api('/api/merge', { a: $('cmpA').value, b: $('cmpB').value });
    session = res.session || session;
    render();
    showMerge(`已生成合并分支，轨迹重放验证：${res.verified ? '通过（指纹一致）' : '未通过'}\n哈希: ${res.traceHash}`);
    toast('合并完成');
  } catch (e) {
    showMerge('合并被拒绝（第一组冲突事件）：\n' + e.message, true);
  }
};

function showMerge(text, isErr) {
  const box = $('mergeResult');
  box.style.display = 'block';
  box.textContent = text;
  box.style.borderColor = isErr ? 'var(--bad)' : 'var(--line)';
}

$('btnExport').onclick = () => {
  fetch('/api/export').then((r) => r.json()).then((bundle) => {
    const blob = new Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'replay-session.json';
    a.click();
    URL.revokeObjectURL(a.href);
  });
};

$('fileImport').onchange = async (ev) => {
  const file = ev.target.files[0];
  if (!file) return;
  try {
    const bundle = JSON.parse(await file.text());
    const data = await api('/api/import', bundle);
    session = data;
    activeBranch = 'main';
    lastDiff = null;
    render();
    toast('会话已导入，轨迹哈希重放校验通过');
  } catch (e) {
    toast('导入失败：' + e.message, true);
  } finally {
    ev.target.value = '';
  }
};

// ---------------- 启动 ----------------

async function boot() {
  try {
    const s = await fetch('/api/session').then((r) => r.json());
    session = s;
    $('defEditor').value = JSON.stringify(session.definition, null, 2);
    render();
  } catch (e) {
    toast('加载会话失败: ' + e.message, true);
  }
}
boot();
