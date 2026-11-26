#!/usr/bin/env node
/*
 * Claude Code Relay Server
 *
 * Runs on the always-on Mac mini. It
 *   - lists Claude Code sessions stored in ~/.claude/projects/<project>/<sessionId>.jsonl
 *   - streams each session's input/output live by tailing its .jsonl file
 *     (so sessions started from the Mac terminal are relayed too)
 *   - sends prompts from the phone into a session with `claude -p --resume <id>`
 *     (or starts a new session with `--session-id <uuid>`)
 *
 * HTTP:  GET /api/health | /api/sessions | /api/sessions/:id/messages?limit=N | /api/projects
 *        POST /api/upload?name=<file>  (raw body, returns { path } to pass as send.attachments)
 * WS:    /ws   (client -> server: subscribe, unsubscribe, send, interrupt)
 * Auth:  "Authorization: Bearer <token>" header, or ?token=<token>
 */
'use strict';

const http = require('http');
const fs = require('fs');
const fsp = fs.promises;
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');
const { WebSocketServer } = require('ws');

// ---------------------------------------------------------------- config
const CONFIG_DIR = path.join(os.homedir(), '.claude-relay');
const CONFIG_FILE = path.join(CONFIG_DIR, 'config.json');
// Files attached from the phone are stored here and passed to claude by absolute path.
const UPLOAD_DIR = path.join(CONFIG_DIR, 'uploads');
const UPLOAD_MAX_BYTES = 30 * 1024 * 1024;
const UPLOAD_KEEP_DAYS = 14;

function loadConfig() {
  let file = {};
  try { file = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8')); } catch (_) { /* first run */ }
  const cfg = {
    port: Number(process.env.RELAY_PORT || file.port || 8787),
    host: process.env.RELAY_HOST || file.host || '0.0.0.0',
    token: process.env.RELAY_TOKEN || file.token || '',
    claudeBin: process.env.CLAUDE_BIN || file.claudeBin || 'claude',
    projectsDir: process.env.CLAUDE_PROJECTS_DIR || file.projectsDir || path.join(os.homedir(), '.claude', 'projects'),
    // New sessions may only be started inside these directories.
    allowedRoots: (process.env.RELAY_ALLOWED_ROOTS ? process.env.RELAY_ALLOWED_ROOTS.split(':') : file.allowedRoots) || [os.homedir()],
    // Permission modes the phone may request. The first one is the default.
    permissionModes: file.permissionModes || ['acceptEdits', 'default', 'plan', 'bypassPermissions'],
    extraArgs: file.extraArgs || [],
  };
  if (!cfg.token) {
    cfg.token = crypto.randomBytes(24).toString('base64url');
    fs.mkdirSync(CONFIG_DIR, { recursive: true });
    fs.writeFileSync(CONFIG_FILE, JSON.stringify({ ...file, token: cfg.token }, null, 2), { mode: 0o600 });
    console.log(`[relay] generated new token and saved it to ${CONFIG_FILE}`);
  }
  return cfg;
}

const cfg = loadConfig();
const log = (...a) => console.log(new Date().toISOString(), '[relay]', ...a);

// ---------------------------------------------------------------- helpers
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function tokenOk(given) {
  if (typeof given !== 'string') return false;
  const a = Buffer.from(given);
  const b = Buffer.from(cfg.token);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function authFromReq(req, url) {
  const h = req.headers['authorization'] || '';
  if (h.startsWith('Bearer ')) return tokenOk(h.slice(7).trim());
  return tokenOk(url.searchParams.get('token'));
}

async function readSlice(file, start, length) {
  const fh = await fsp.open(file, 'r');
  try {
    const buf = Buffer.alloc(length);
    const { bytesRead } = await fh.read(buf, 0, length, start);
    return buf.subarray(0, bytesRead).toString('utf8');
  } finally {
    await fh.close();
  }
}

function parseLines(text, { dropFirst = false, dropLast = false } = {}) {
  const lines = text.split('\n');
  if (dropFirst) lines.shift();
  if (dropLast) lines.pop();
  const out = [];
  for (const l of lines) {
    if (!l.trim()) continue;
    try { out.push(JSON.parse(l)); } catch (_) { /* partial / corrupt line */ }
  }
  return out;
}

function truncate(s, n) {
  if (typeof s !== 'string') s = s == null ? '' : String(s);
  return s.length > n ? s.slice(0, n) + `\n… (${s.length - n} more chars)` : s;
}

function isInside(child, parent) {
  const rel = path.relative(parent, child);
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel));
}

// ---------------------------------------------------------------- transcript -> simplified messages
function toolInputSummary(name, input) {
  if (!input || typeof input !== 'object') return '';
  const pick = input.command || input.file_path || input.path || input.pattern || input.url ||
    input.query || input.description || input.prompt || input.skill;
  if (typeof pick === 'string') return truncate(pick, 400);
  return truncate(JSON.stringify(input), 400);
}

function toolResultText(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content.map((c) => (c && c.type === 'text' ? c.text : c && c.type === 'image' ? '[image]' : '')).join('\n');
  }
  return '';
}

/** Converts one .jsonl transcript entry into a message for the app, or null to skip it. */
function simplify(entry) {
  if (!entry || (entry.type !== 'user' && entry.type !== 'assistant')) return null;
  if (entry.isSidechain || entry.isMeta || !entry.message) return null;
  const content = entry.message.content;
  const blocks = [];
  if (typeof content === 'string') {
    blocks.push({ kind: 'text', text: content });
  } else if (Array.isArray(content)) {
    for (const c of content) {
      if (!c) continue;
      switch (c.type) {
        case 'text':
          if (c.text && c.text.trim()) blocks.push({ kind: 'text', text: c.text });
          break;
        case 'thinking':
          if (c.thinking && c.thinking.trim()) blocks.push({ kind: 'thinking', text: truncate(c.thinking, 4000) });
          break;
        case 'tool_use':
          blocks.push({ kind: 'tool_use', id: c.id, name: c.name, text: toolInputSummary(c.name, c.input) });
          break;
        case 'tool_result':
          blocks.push({ kind: 'tool_result', id: c.tool_use_id, isError: !!c.is_error, text: truncate(toolResultText(c.content), 3000) });
          break;
        case 'image':
          blocks.push({ kind: 'text', text: '[image]' });
          break;
        default:
          break;
      }
    }
  }
  if (!blocks.length) return null;
  return {
    uuid: entry.uuid || crypto.randomUUID(),
    ts: entry.timestamp || null,
    role: entry.type,
    blocks,
  };
}

// ---------------------------------------------------------------- session index
const metaCache = new Map(); // file -> { mtimeMs, size, meta }

function textOfUser(entry) {
  const c = entry.message && entry.message.content;
  if (typeof c === 'string') return c;
  if (Array.isArray(c)) {
    const t = c.find((x) => x && x.type === 'text');
    return t ? t.text : null;
  }
  return null;
}

async function sessionMeta(file, st) {
  const cached = metaCache.get(file);
  if (cached && cached.mtimeMs === st.mtimeMs && cached.size === st.size) return cached.meta;

  const id = path.basename(file, '.jsonl');
  const CHUNK = 128 * 1024;
  const head = parseLines(await readSlice(file, 0, Math.min(CHUNK, st.size)), { dropLast: st.size > CHUNK });
  const tailStart = Math.max(0, st.size - CHUNK);
  const tail = tailStart === 0 ? head : parseLines(await readSlice(file, tailStart, st.size - tailStart), { dropFirst: true });

  let cwd = null; let firstPrompt = null; let title = null; let lastPrompt = null;
  for (const e of head) {
    if (!cwd && e.cwd) cwd = e.cwd;
    if (!firstPrompt && e.type === 'user' && !e.isMeta && !e.isSidechain) {
      const t = textOfUser(e);
      if (t && !t.startsWith('<')) firstPrompt = t;
    }
  }
  for (const e of [...head, ...tail]) {
    if (e.type === 'custom-title' && e.customTitle) title = e.customTitle;
    else if (e.type === 'ai-title' && e.aiTitle && !title) title = e.aiTitle;
    else if (e.type === 'summary' && e.summary && !title) title = e.summary;
    if (e.type === 'last-prompt' && e.lastPrompt) lastPrompt = e.lastPrompt;
    if (!cwd && e.cwd) cwd = e.cwd;
  }
  const meta = {
    id,
    cwd,
    project: path.basename(path.dirname(file)),
    title: title || truncate(firstPrompt || lastPrompt || '(no prompt)', 80),
    lastPrompt: lastPrompt ? truncate(lastPrompt, 200) : null,
    updatedAt: new Date(st.mtimeMs).toISOString(),
    size: st.size,
  };
  metaCache.set(file, { mtimeMs: st.mtimeMs, size: st.size, meta });
  return meta;
}

/** Returns all session files: Map<sessionId, absPath>. */
async function scanSessionFiles() {
  const result = new Map();
  let projects = [];
  try { projects = await fsp.readdir(cfg.projectsDir, { withFileTypes: true }); } catch (_) { return result; }
  for (const p of projects) {
    if (!p.isDirectory()) continue;
    const dir = path.join(cfg.projectsDir, p.name);
    let files = [];
    try { files = await fsp.readdir(dir); } catch (_) { continue; }
    for (const f of files) {
      if (!f.endsWith('.jsonl')) continue;
      const id = f.slice(0, -6);
      if (UUID_RE.test(id)) result.set(id, path.join(dir, f));
    }
  }
  return result;
}

let fileIndex = new Map();
async function findSessionFile(id) {
  if (!UUID_RE.test(id)) return null;
  if (fileIndex.has(id)) {
    const f = fileIndex.get(id);
    if (fs.existsSync(f)) return f;
  }
  fileIndex = await scanSessionFiles();
  return fileIndex.get(id) || null;
}

async function listSessions(limit = 200) {
  fileIndex = await scanSessionFiles();
  const stats = [];
  for (const [id, file] of fileIndex) {
    try {
      const st = await fsp.stat(file);
      if (st.size > 0) stats.push({ id, file, st });
    } catch (_) { /* removed meanwhile */ }
  }
  stats.sort((a, b) => b.st.mtimeMs - a.st.mtimeMs);
  const out = [];
  for (const s of stats.slice(0, limit)) {
    try {
      const meta = await sessionMeta(s.file, s.st);
      out.push({ ...meta, running: runs.has(s.id) });
    } catch (e) { log('meta error', s.file, e.message); }
  }
  // sessions just started from the phone may not have a file yet
  for (const [id, r] of runs) {
    if (!out.some((m) => m.id === id)) {
      out.unshift({ id, cwd: r.cwd, project: null, title: truncate(r.prompt, 80), lastPrompt: truncate(r.prompt, 200), updatedAt: new Date(r.startedAt).toISOString(), size: 0, running: true });
    }
  }
  return out;
}

async function readHistory(id, limit) {
  const file = await findSessionFile(id);
  if (!file) return { messages: [], offset: 0 };
  const st = await fsp.stat(file);
  // read at most the last 8 MB to bound memory
  const MAX = 8 * 1024 * 1024;
  const start = Math.max(0, st.size - MAX);
  const text = await readSlice(file, start, st.size - start);
  const entries = parseLines(text, { dropFirst: start > 0 });
  const msgs = entries.map(simplify).filter(Boolean);
  return { messages: msgs.slice(-limit), offset: st.size };
}

// ---------------------------------------------------------------- live tailing
// sessionId -> { file, offset, remainder, clients:Set<ws>, timer }
const tails = new Map();

function broadcast(obj, clients) {
  const data = JSON.stringify(obj);
  for (const ws of clients || wss.clients) {
    if (ws.readyState === 1 && ws.authed) ws.send(data);
  }
}

async function pollTail(id) {
  const t = tails.get(id);
  if (!t || t.busy) return;
  t.busy = true;
  try {
    if (!t.file) {
      t.file = await findSessionFile(id);
      if (!t.file) return;
      t.offset = 0; // file appeared after subscribe: relay everything
    }
    let st;
    try { st = await fsp.stat(t.file); } catch (_) { return; }
    if (st.size < t.offset) { t.offset = 0; t.remainder = ''; } // truncated / rewritten
    if (st.size === t.offset) return;
    const chunk = await readSlice(t.file, t.offset, st.size - t.offset);
    t.offset = st.size;
    const text = t.remainder + chunk;
    const lastNl = text.lastIndexOf('\n');
    t.remainder = lastNl === -1 ? text : text.slice(lastNl + 1);
    const complete = lastNl === -1 ? '' : text.slice(0, lastNl);
    for (const e of parseLines(complete)) {
      const m = simplify(e);
      if (m) broadcast({ type: 'message', sessionId: id, message: m }, t.clients);
    }
  } catch (e) {
    log('tail error', id, e.message);
  } finally {
    t.busy = false;
  }
}

async function subscribe(ws, id, fromOffset) {
  let t = tails.get(id);
  if (!t) {
    const file = await findSessionFile(id);
    let offset = 0;
    if (file) { try { offset = (await fsp.stat(file)).size; } catch (_) { /* ignore */ } }
    t = { file, offset, remainder: '', clients: new Set(), busy: false };
    t.timer = setInterval(() => pollTail(id), 500);
    tails.set(id, t);
  }
  // A client that fetched history up to `fromOffset` gets the gap replayed.
  if (typeof fromOffset === 'number' && t.file && fromOffset < t.offset) {
    try {
      const gap = await readSlice(t.file, fromOffset, t.offset - fromOffset);
      for (const e of parseLines(gap.slice(0, gap.lastIndexOf('\n') + 1))) {
        const m = simplify(e);
        if (m && ws.readyState === 1) ws.send(JSON.stringify({ type: 'message', sessionId: id, message: m }));
      }
    } catch (_) { /* ignore */ }
  }
  t.clients.add(ws);
  ws.subs.add(id);
}

function unsubscribe(ws, id) {
  ws.subs.delete(id);
  const t = tails.get(id);
  if (!t) return;
  t.clients.delete(ws);
  if (!t.clients.size) { clearInterval(t.timer); tails.delete(id); }
}

// ---------------------------------------------------------------- running claude
// sessionId -> { proc, cwd, prompt, startedAt, stderr }
const runs = new Map();

function runningList() { return [...runs.keys()]; }

async function startRun({ sessionId, cwd, text, permissionMode, model, attachments }) {
  if (typeof text !== 'string') text = '';
  const files = await checkAttachments(attachments);
  if (!text.trim() && !files.length) throw new Error('empty prompt');
  if (files.length) {
    text = (text.trim() || '첨부한 파일을 확인해 주세요.') +
      '\n\n[휴대폰에서 첨부한 파일 — 맥미니에 저장됨, Read 도구로 열어 보세요]\n' +
      files.map((f) => `- ${f}`).join('\n');
  }
  const isNew = !sessionId;
  if (isNew) {
    sessionId = crypto.randomUUID();
    if (typeof cwd !== 'string' || !path.isAbsolute(cwd)) throw new Error('cwd must be an absolute path');
    cwd = path.resolve(cwd);
    if (!cfg.allowedRoots.some((r) => isInside(cwd, path.resolve(r)))) throw new Error(`cwd is outside allowed roots: ${cfg.allowedRoots.join(', ')}`);
    const st = await fsp.stat(cwd).catch(() => null);
    if (!st || !st.isDirectory()) throw new Error(`directory not found: ${cwd}`);
  } else {
    if (!UUID_RE.test(sessionId)) throw new Error('bad session id');
    if (runs.has(sessionId)) throw new Error('this session is already running; wait or interrupt it');
    const file = await findSessionFile(sessionId);
    if (!file) throw new Error('session not found');
    const st = await fsp.stat(file);
    cwd = (await sessionMeta(file, st)).cwd;
    if (!cwd || !fs.existsSync(cwd)) throw new Error(`session working directory missing: ${cwd}`);
  }
  const mode = cfg.permissionModes.includes(permissionMode) ? permissionMode : cfg.permissionModes[0];

  const args = ['-p', '--output-format', 'stream-json', '--verbose', '--include-partial-messages', '--permission-mode', mode];
  args.push(...(isNew ? ['--session-id', sessionId] : ['--resume', sessionId]));
  if (typeof model === 'string' && /^[\w.\-\[\]]{1,64}$/.test(model)) args.push('--model', model);
  if (files.length) args.push('--add-dir', UPLOAD_DIR);
  args.push(...cfg.extraArgs);

  log(`run ${isNew ? 'new' : 'resume'} ${sessionId} in ${cwd} (mode=${mode})`);
  const proc = spawn(cfg.claudeBin, args, { cwd, env: process.env, stdio: ['pipe', 'pipe', 'pipe'] });
  const run = { proc, cwd, prompt: text, startedAt: Date.now(), stderr: '', result: null };
  runs.set(sessionId, run);
  proc.stdin.end(text);

  let buf = '';
  proc.stdout.on('data', (d) => {
    buf += d.toString('utf8');
    let i;
    while ((i = buf.indexOf('\n')) !== -1) {
      const line = buf.slice(0, i); buf = buf.slice(i + 1);
      if (!line.trim()) continue;
      let ev; try { ev = JSON.parse(line); } catch (_) { continue; }
      handleStreamEvent(sessionId, ev, run);
    }
  });
  proc.stderr.on('data', (d) => { run.stderr = (run.stderr + d.toString('utf8')).slice(-4000); });
  proc.on('error', (e) => { run.stderr += `\n${e.message}`; });
  proc.on('close', (code, signal) => {
    runs.delete(sessionId);
    const r = run.result || {};
    const ok = code === 0 && !r.is_error;
    log(`run done ${sessionId} code=${code} signal=${signal}`);
    setTimeout(() => pollTail(sessionId), 100);
    broadcast({
      type: 'run_finished', sessionId, ok, interrupted: !!run.interrupted,
      exitCode: code, costUsd: r.total_cost_usd ?? null, durationMs: Date.now() - run.startedAt,
      error: ok ? null : truncate((r.result && r.is_error ? r.result : '') || run.stderr || `exit code ${code}`, 2000),
      title: truncate(run.prompt, 80),
    });
  });

  broadcast({ type: 'run_started', sessionId, cwd, isNew, prompt: truncate(text, 200) });
  return { sessionId, cwd, isNew };
}

function handleStreamEvent(sessionId, ev, run) {
  if (ev.type === 'stream_event' && ev.event && !ev.parent_tool_use_id) {
    const e = ev.event;
    if (e.type === 'content_block_delta' && e.delta && e.delta.type === 'text_delta') {
      const t = tails.get(sessionId);
      if (t) broadcast({ type: 'delta', sessionId, text: e.delta.text }, t.clients);
    }
  } else if (ev.type === 'result' || (ev.total_cost_usd !== undefined && ev.session_id)) {
    run.result = ev;
  }
}

function interrupt(sessionId) {
  const r = runs.get(sessionId);
  if (!r) return false;
  r.interrupted = true;
  r.proc.kill('SIGINT');
  setTimeout(() => { if (runs.get(sessionId) === r) r.proc.kill('SIGKILL'); }, 5000);
  return true;
}

// ---------------------------------------------------------------- attachments
function safeFileName(name) {
  let n = String(name || '').split(/[\\/]/).pop().replace(/[\x00-\x1f<>:"|?*]/g, '_').trim();
  n = n.replace(/^\.+/, '');
  if (!n) n = 'file';
  if (Buffer.byteLength(n) > 120) {
    const ext = path.extname(n).slice(0, 16);
    n = Buffer.from(n).subarray(0, 100).toString('utf8').replace(/\uFFFD/g, '') + ext;
  }
  return n;
}

async function saveUpload(req, name) {
  const declared = Number(req.headers['content-length'] || 0);
  if (declared > UPLOAD_MAX_BYTES) throw Object.assign(new Error(`파일이 너무 큽니다 (최대 ${UPLOAD_MAX_BYTES / 1048576}MB)`), { status: 413 });
  const day = new Date().toISOString().slice(0, 10);
  const dir = path.join(UPLOAD_DIR, day);
  await fsp.mkdir(dir, { recursive: true, mode: 0o700 });
  const file = path.join(dir, `${crypto.randomBytes(4).toString('hex')}-${safeFileName(name)}`);
  const out = fs.createWriteStream(file, { mode: 0o600 });
  let size = 0;
  try {
    await new Promise((resolve, reject) => {
      req.on('data', (d) => {
        size += d.length;
        if (size > UPLOAD_MAX_BYTES) { req.destroy(); reject(Object.assign(new Error('파일이 너무 큽니다'), { status: 413 })); return; }
        if (!out.write(d)) { req.pause(); out.once('drain', () => req.resume()); }
      });
      req.on('end', resolve);
      req.on('error', reject);
      out.on('error', reject);
    });
    await new Promise((r) => out.end(r));
  } catch (e) {
    out.destroy();
    await fsp.unlink(file).catch(() => {});
    throw e;
  }
  if (!size) { await fsp.unlink(file).catch(() => {}); throw Object.assign(new Error('빈 파일'), { status: 400 }); }
  log(`upload ${file} (${size} bytes)`);
  return { path: file, name: path.basename(file).slice(9), size };
}

async function checkAttachments(list) {
  if (list == null) return [];
  if (!Array.isArray(list) || list.length > 20) throw new Error('bad attachments');
  const out = [];
  for (const p of list) {
    if (typeof p !== 'string' || !path.isAbsolute(p)) throw new Error('bad attachment path');
    const abs = path.resolve(p);
    if (!isInside(abs, UPLOAD_DIR) || abs === UPLOAD_DIR) throw new Error('attachment outside upload dir');
    const st = await fsp.stat(abs).catch(() => null);
    if (!st || !st.isFile()) throw new Error(`첨부 파일이 없습니다: ${path.basename(abs)}`);
    out.push(abs);
  }
  return out;
}

async function pruneUploads() {
  const cutoff = Date.now() - UPLOAD_KEEP_DAYS * 86400000;
  let days = [];
  try { days = await fsp.readdir(UPLOAD_DIR); } catch (_) { return; }
  for (const d of days) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(d)) continue;
    if (Date.parse(d + 'T00:00:00Z') < cutoff) {
      await fsp.rm(path.join(UPLOAD_DIR, d), { recursive: true, force: true }).catch(() => {});
      log('pruned uploads', d);
    }
  }
}
pruneUploads();
setInterval(pruneUploads, 6 * 3600 * 1000).unref();

// ---------------------------------------------------------------- HTTP
function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  try {
    if (url.pathname === '/api/health') return sendJson(res, 200, { ok: true, version: 1 });
    if (!url.pathname.startsWith('/api/')) return sendJson(res, 404, { error: 'not found' });
    if (!authFromReq(req, url)) return sendJson(res, 401, { error: 'unauthorized' });

    if (req.method === 'GET' && url.pathname === '/api/sessions') {
      const limit = Math.min(500, Number(url.searchParams.get('limit')) || 100);
      return sendJson(res, 200, { sessions: await listSessions(limit), running: runningList(), permissionModes: cfg.permissionModes });
    }
    if (req.method === 'POST' && url.pathname === '/api/upload') {
      return sendJson(res, 200, await saveUpload(req, url.searchParams.get('name')));
    }
    if (req.method === 'GET' && url.pathname === '/api/projects') {
      const sessions = await listSessions(500);
      const seen = new Map();
      for (const s of sessions) if (s.cwd && !seen.has(s.cwd)) seen.set(s.cwd, s.updatedAt);
      return sendJson(res, 200, { projects: [...seen].map(([cwd, updatedAt]) => ({ cwd, updatedAt })), allowedRoots: cfg.allowedRoots });
    }
    const m = url.pathname.match(/^\/api\/sessions\/([0-9a-f-]{36})\/messages$/i);
    if (req.method === 'GET' && m) {
      const limit = Math.min(2000, Number(url.searchParams.get('limit')) || 300);
      const h = await readHistory(m[1], limit);
      return sendJson(res, 200, { sessionId: m[1], running: runs.has(m[1]), ...h });
    }
    return sendJson(res, 404, { error: 'not found' });
  } catch (e) {
    if (!e.status) log('http error', e);
    if (res.headersSent) return res.end();
    return sendJson(res, e.status || 500, { error: e.message });
  }
});

// ---------------------------------------------------------------- WebSocket
const wss = new WebSocketServer({ noServer: true, maxPayload: 1024 * 1024 });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname !== '/ws' || !authFromReq(req, url)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
});

wss.on('connection', (ws, req) => {
  ws.authed = true;
  ws.subs = new Set();
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  log('client connected', req.socket.remoteAddress);
  ws.send(JSON.stringify({ type: 'hello', running: runningList(), permissionModes: cfg.permissionModes, host: os.hostname() }));

  ws.on('message', async (raw) => {
    let msg; try { msg = JSON.parse(raw.toString()); } catch (_) { return; }
    const reply = (o) => ws.readyState === 1 && ws.send(JSON.stringify({ reqId: msg.reqId, ...o }));
    try {
      switch (msg.type) {
        case 'subscribe':
          if (!UUID_RE.test(msg.sessionId || '')) throw new Error('bad session id');
          await subscribe(ws, msg.sessionId, msg.fromOffset);
          return reply({ type: 'subscribed', sessionId: msg.sessionId, running: runs.has(msg.sessionId) });
        case 'unsubscribe':
          unsubscribe(ws, msg.sessionId);
          return reply({ type: 'unsubscribed', sessionId: msg.sessionId });
        case 'send': {
          const r = await startRun(msg);
          if (r.isNew) await subscribe(ws, r.sessionId, 0);
          return reply({ type: 'sent', ...r });
        }
        case 'interrupt':
          return reply({ type: 'interrupted', sessionId: msg.sessionId, ok: interrupt(msg.sessionId) });
        case 'ping':
          return reply({ type: 'pong' });
        default:
          throw new Error(`unknown type ${msg.type}`);
      }
    } catch (e) {
      reply({ type: 'error', error: e.message, sessionId: msg.sessionId || null });
    }
  });

  ws.on('close', () => { for (const id of [...ws.subs]) unsubscribe(ws, id); });
});

setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    try { ws.ping(); } catch (_) { /* ignore */ }
  }
}, 30000);

function lanAddresses() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) if (a.family === 'IPv4' && !a.internal) out.push(a.address);
  }
  return out;
}

server.listen(cfg.port, cfg.host, () => {
  log(`listening on ${cfg.host}:${cfg.port}`);
  log(`projects dir: ${cfg.projectsDir}`);
  for (const ip of lanAddresses()) log(`app server URL: http://${ip}:${cfg.port}`);
  log(`token: ${cfg.token}`);
});

function shutdown() {
  log('shutting down');
  for (const id of runs.keys()) interrupt(id);
  server.close();
  setTimeout(() => process.exit(0), 1500).unref();
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
