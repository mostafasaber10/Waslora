const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawn } = require('node:child_process');
const { randomUUID } = require('node:crypto');

const HOST = process.env.WASLORA_RESOLVER_HOST || '127.0.0.1';
const PORT = Number(process.env.WASLORA_RESOLVER_PORT || 8787);
const TOKEN = process.env.WASLORA_RESOLVER_TOKEN || '';
const ROOT = path.resolve(process.env.WASLORA_JOB_DIR || path.join(os.tmpdir(), 'waslora-resolver'));
const MAX_BODY = 32 * 1024;
// Keep the default useful for a small shared server while allowing an
// operator to scale concurrency up or down for the available CPU/network.
const MAX_CONCURRENT = Math.max(1, Math.min(16, Number(process.env.WASLORA_MAX_JOBS || 4)));
const MAX_DURATION_SECONDS = Math.max(60, Number(process.env.WASLORA_MAX_DURATION_SECONDS || 7200));
const MAX_FILE_BYTES = Math.max(64 * 1024 * 1024, Number(process.env.WASLORA_MAX_FILE_BYTES || 100 * 1024 * 1024 * 1024));
const JOB_TTL_MS = Math.max(5 * 60 * 1000, Number(process.env.WASLORA_JOB_TTL_MS || 30 * 60 * 1000));
const configuredRuntime = Number(process.env.WASLORA_MAX_RUNTIME_MS);
const MAX_JOB_RUNTIME_MS = Number.isFinite(configuredRuntime)
  ? Math.max(2 * 60 * 1000, configuredRuntime)
  : (MAX_DURATION_SECONDS + 120) * 1000;
const ALLOW_INSECURE_LAN = process.env.WASLORA_RESOLVER_ALLOW_INSECURE_LAN === 'true';
const VERSION = '0.2.0';
let YTDLP_PREFIX = [];
try {
  YTDLP_PREFIX = process.env.WASLORA_YTDLP_ARGS_PREFIX ? JSON.parse(process.env.WASLORA_YTDLP_ARGS_PREFIX) : [];
  if (!Array.isArray(YTDLP_PREFIX) || YTDLP_PREFIX.some((value) => typeof value !== 'string')) throw new Error('prefix must be a string array');
} catch (error) {
  throw new Error(`WASLORA_YTDLP_ARGS_PREFIX must be a JSON string array: ${error.message}`);
}
const PLATFORM_DOMAINS = [
  'youtube.com', 'youtube-nocookie.com', 'youtu.be',
  'facebook.com', 'fb.com', 'fb.watch', 'fb.me',
  'instagram.com', 'instagr.am', 'tiktok.com',
  'twitter.com', 'x.com', 'vimeo.com', 'dailymotion.com', 'twitch.tv',
  'reddit.com', 'redd.it', 'pinterest.com', 'snapchat.com',
];
const jobs = new Map();
let activeJobs = 0;

function isPlatformHost(hostname) {
  const host = hostname.toLowerCase();
  return PLATFORM_DOMAINS.some((domain) => host === domain || host.endsWith(`.${domain}`));
}

function authenticate(request) {
  if (!TOKEN) return true;
  return request.headers.authorization === `Bearer ${TOKEN}`;
}

function json(response, status, body) {
  const payload = Buffer.from(JSON.stringify(body));
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': payload.length,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'",
  });
  response.end(payload);
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    request.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        reject(Object.assign(new Error('request body too large'), { status: 413 }));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on('end', () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch (_) { reject(Object.assign(new Error('invalid JSON'), { status: 400 })); }
    });
    request.on('error', reject);
  });
}

function validateUrl(value) {
  if (typeof value !== 'string' || value.trim() === '') throw Object.assign(new Error('url is required'), { status: 400 });
  let parsed;
  try { parsed = new URL(value.trim()); }
  catch (_) { throw Object.assign(new Error('url is invalid'), { status: 400 }); }
  if (!['http:', 'https:'].includes(parsed.protocol)) throw Object.assign(new Error('only http and https URLs are supported'), { status: 400 });
  if (parsed.username || parsed.password) throw Object.assign(new Error('URLs with embedded credentials are not accepted'), { status: 400 });
  if (!isPlatformHost(parsed.hostname)) throw Object.assign(new Error('only supported public video platforms are accepted by this resolver'), { status: 400 });
  return parsed;
}

function safeError(text) {
  const clean = String(text || '').split(/\r?\n/).filter(Boolean).slice(-8).join(' ').slice(-900);
  if (/login required|sign in|log in|authentication|cookies/i.test(clean)) {
    return 'هذا الفيديو يتطلب تسجيل الدخول أو صلاحية خاصة. الخدمة لا تستخدم ملفات جلسة أو كلمات مرور.';
  }
  if (/unsupported url/i.test(clean)) return 'صيغة الرابط أو هذا المحتوى غير مدعوم حاليًا. جرّب رابط الفيديو الكامل من المنصة.';
  if (/private|members.only|not available|unavailable|removed/i.test(clean)) return 'الفيديو خاص أو محذوف أو غير متاح للتنزيل من مصدره.';
  if (/drm|widevine|encrypted/i.test(clean)) return 'المحتوى محمي ولا يمكن تنزيله بهذه الخدمة.';
  return 'تعذر تنزيل الفيديو. قد يكون الرابط غير متاح أو تغيّرت طريقة المنصة؛ جرّب لاحقًا.';
}

function makeJob(url) {
  const id = randomUUID();
  const directory = path.join(ROOT, id);
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
  const job = {
    id, url, directory, state: 'queued', progress: 0, speed: '', eta: '',
    createdAt: Date.now(), finishedAt: null, error: null, file: null, process: null, cancelled: false,
  };
  jobs.set(id, job);
  return job;
}

function publicJob(job) {
  const result = {
    id: job.id,
    status: job.state,
    progress: job.progress,
    speed: job.speed,
    eta: job.eta,
  };
  if (job.state === 'ready' && job.file) {
    result.fileUrl = `/v1/jobs/${job.id}/file`;
    result.filename = path.basename(job.file);
    result.size = fs.statSync(job.file).size;
  }
  if (job.state === 'error') result.error = job.error;
  return result;
}

function updateProgress(job, text) {
  const percent = text.match(/\[download\]\s+([\d.]+)%/);
  if (percent) job.progress = Math.max(0, Math.min(99, Number(percent[1])));
  const speed = text.match(/at\s+([^\s]+\/s)/i);
  if (speed) job.speed = speed[1];
  const eta = text.match(/ETA\s+([^\s]+)/i);
  if (eta) job.eta = eta[1];
}

function startDownload(job) {
  activeJobs++;
  job.state = 'downloading';
  const binary = process.env.WASLORA_YTDLP_BIN || 'yt-dlp';
  const args = [
    '--ignore-config', '--no-cookies', '--no-playlist', '--no-warnings', '--newline', '--progress-delta', '1',
    '--match-filter', `duration <= ${MAX_DURATION_SECONDS}`,
    '--max-filesize', String(MAX_FILE_BYTES),
    '-f', 'best[ext=mp4]/best',
    '--output', path.join(job.directory, '%(title).100B-%(id)s.%(ext)s'),
    job.url,
  ];
  const child = spawn(binary, [...YTDLP_PREFIX, ...args], { cwd: job.directory, windowsHide: true, shell: false, stdio: ['ignore', 'ignore', 'pipe'] });
  job.process = child;
  job.timeout = setTimeout(() => {
    if (!job.process || job.state !== 'downloading') return;
    job.cancelled = true;
    job.error = 'استغرق التنزيل وقتًا أطول من الحد المسموح للخادم.';
    try { job.process.kill('SIGTERM'); } catch (_) {}
  }, MAX_JOB_RUNTIME_MS);
  let stderr = '';
  let lineBuffer = '';
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk) => {
    stderr = (stderr + chunk).slice(-12000);
    lineBuffer += chunk;
    const lines = lineBuffer.split(/\r?\n/);
    lineBuffer = lines.pop() || '';
    for (const line of lines) updateProgress(job, line);
    updateProgress(job, lineBuffer);
  });
  child.on('error', (error) => {
    if (job.timeout) clearTimeout(job.timeout);
    job.state = 'error';
    job.error = error.code === 'ENOENT'
      ? 'خدمة التنزيل غير مثبتة على الخادم. شغّل إعداد Docker الموجود في المشروع.'
      : safeError(error.message);
    job.finishedAt = Date.now();
    job.process = null;
    activeJobs--;
  });
  child.on('close', (code) => {
    if (job.timeout) clearTimeout(job.timeout);
    if (job.cancelled) {
      job.state = 'error';
      job.error ||= 'تم إلغاء مهمة التنزيل.';
      job.finishedAt = Date.now();
      if (job.process !== null) activeJobs = Math.max(0, activeJobs - 1);
      job.process = null;
      return;
    }
    if (job.state === 'error') return;
    job.process = null;
    activeJobs--;
    let files;
    try {
      files = fs.readdirSync(job.directory)
      .map((name) => path.join(job.directory, name))
      .filter((file) => {
        try { return fs.statSync(file).isFile() && !/\.(part|ytdl|json|description|jpg|png|webp|vtt|srt)$/i.test(file); }
        catch (_) { return false; }
      });
    } catch (_) {
      job.state = 'error';
      job.error = 'ملفات المهمة غير متاحة؛ أعد المحاولة.';
      job.finishedAt = Date.now();
      return;
    }
    if (code !== 0 || files.length === 0) {
      job.state = 'error';
      job.error = safeError(stderr);
    } else {
      const file = files[0];
      const stat = fs.statSync(file);
      if (stat.size <= 0 || stat.size > MAX_FILE_BYTES) {
        job.state = 'error';
        job.error = 'حجم الفيديو غير صالح أو يتجاوز الحد المسموح للخادم.';
      } else {
        job.file = file;
        job.progress = 100;
        job.state = 'ready';
      }
    }
    job.finishedAt = Date.now();
  });
}

function cleanupExpiredJobs() {
  const now = Date.now();
  for (const [id, job] of jobs) {
    if (job.state === 'downloading' || job.state === 'queued') continue;
    const expiry = job.finishedAt || job.createdAt;
    if (now - expiry < JOB_TTL_MS) continue;
    jobs.delete(id);
    fs.rm(job.directory, { recursive: true, force: true }, () => {});
  }
}

function streamFile(job, response) {
  const file = job.file;
  if (!file || !fs.existsSync(file)) {
    json(response, 404, { ok: false, code: 'file_expired' });
    return;
  }
  const stat = fs.statSync(file);
  const ascii = path.basename(file).replace(/[^A-Za-z0-9._-]/g, '_') || 'video.mp4';
  const extension = path.extname(file).toLowerCase();
  const contentType = extension === '.webm' ? 'video/webm'
    : extension === '.mkv' ? 'video/x-matroska'
      : extension === '.mov' ? 'video/quicktime'
        : extension === '.3gp' ? 'video/3gpp' : 'video/mp4';
  response.writeHead(200, {
    'Content-Type': contentType,
    'Content-Length': stat.size,
    'Content-Disposition': `attachment; filename="${ascii}"`,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  });
  const input = fs.createReadStream(file);
  input.on('error', () => { if (!response.headersSent) json(response, 500, { ok: false, code: 'read_error' }); else response.destroy(); });
  input.pipe(response);
}

async function handle(request, response) {
  if (!authenticate(request)) {
    json(response, 401, { ok: false, code: 'unauthorized' });
    return;
  }
  if (request.method === 'GET' && request.url === '/health') {
    json(response, 200, { ok: true, service: 'waslora-resolver', version: VERSION });
    return;
  }
  if (request.method === 'POST' && request.url === '/v1/jobs') {
    const body = await readJson(request);
    const parsed = validateUrl(body.url);
    if (activeJobs >= MAX_CONCURRENT) {
      json(response, 429, { ok: false, code: 'busy', message: 'الخادم ينزّل الحد الأقصى حاليًا. أعد المحاولة بعد قليل.' });
      return;
    }
    const job = makeJob(parsed.toString());
    startDownload(job);
    json(response, 202, { ok: true, ...publicJob(job), statusUrl: `/v1/jobs/${job.id}` });
    return;
  }
  const match = request.url.match(/^\/v1\/jobs\/([0-9a-f-]{36})(?:\/(file))?$/i);
  if (match && request.method === 'GET') {
    const job = jobs.get(match[1]);
    if (!job) { json(response, 404, { ok: false, code: 'job_not_found' }); return; }
    if (match[2] === 'file') {
      if (job.state !== 'ready') { json(response, 409, { ok: false, code: 'not_ready', status: job.state }); return; }
      streamFile(job, response);
      return;
    }
    json(response, 200, { ok: true, ...publicJob(job) });
    return;
  }
  if (match && request.method === 'DELETE') {
    const job = jobs.get(match[1]);
    if (!job) { json(response, 404, { ok: false, code: 'job_not_found' }); return; }
    if (job.process) { job.cancelled = true; job.process.kill('SIGTERM'); }
    jobs.delete(job.id);
    fs.rm(job.directory, { recursive: true, force: true }, () => {});
    json(response, 200, { ok: true, cancelled: true });
    return;
  }
  json(response, 404, { ok: false, code: 'not_found' });
}

const server = http.createServer((request, response) => {
  handle(request, response).catch((error) => {
    if (!response.headersSent && !response.destroyed) {
      const status = Number.isInteger(error.status) ? error.status : 500;
      json(response, status, { ok: false, code: status === 500 ? 'internal_error' : 'bad_request', message: error.message || 'request failed' });
    }
  });
});

server.requestTimeout = 30_000;
server.headersTimeout = 15_000;
server.keepAliveTimeout = 5_000;
server.maxRequestsPerSocket = 100;

fs.mkdirSync(ROOT, { recursive: true, mode: 0o700 });
const isLoopbackHost = HOST === '127.0.0.1' || HOST === 'localhost' || HOST === '::1';
if (!isLoopbackHost && !TOKEN) {
  throw new Error('Set WASLORA_RESOLVER_TOKEN before binding the Resolver outside localhost.');
}
if (!isLoopbackHost && TOKEN.length < 32) {
  throw new Error('WASLORA_RESOLVER_TOKEN must be at least 32 characters outside localhost.');
}
if (!isLoopbackHost && !ALLOW_INSECURE_LAN) {
  throw new Error('Resolver is plain HTTP. Keep it on localhost or set WASLORA_RESOLVER_ALLOW_INSECURE_LAN=true only on a trusted private LAN; use HTTPS for public access.');
}
server.listen(PORT, HOST, () => process.stdout.write(`Waslora Resolver listening on http://${HOST}:${PORT}\n`));
setInterval(cleanupExpiredJobs, 60_000).unref();

process.on('SIGTERM', () => {
  for (const job of jobs.values()) {
    if (job.timeout) clearTimeout(job.timeout);
    if (job.process) job.process.kill('SIGTERM');
  }
  server.close(() => process.exit(0));
});
