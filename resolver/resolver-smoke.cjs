const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const http = require('node:http');
const path = require('node:path');
const fs = require('node:fs');

const port = 8877;
const jobDir = path.join(__dirname, '.smoke-jobs');
fs.rmSync(jobDir, { recursive: true, force: true });
const child = spawn(process.execPath, [path.join(__dirname, 'server.js')], {
  cwd: __dirname,
  env: {
    ...process.env,
    WASLORA_RESOLVER_PORT: String(port),
    WASLORA_RESOLVER_TOKEN: 'smoke-token',
    WASLORA_JOB_DIR: jobDir,
    WASLORA_YTDLP_BIN: process.execPath,
    WASLORA_YTDLP_ARGS_PREFIX: JSON.stringify([path.join(__dirname, 'fake-ytdlp.cjs')]),
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});

function request(method, route, body, token) {
  return new Promise((resolve, reject) => {
    const req = http.request(`http://127.0.0.1:${port}${route}`, { method, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => {
        const bytes = Buffer.concat(chunks);
        let parsed = null;
        try { parsed = JSON.parse(bytes.toString('utf8')); } catch (_) {}
        resolve({ status: response.statusCode, headers: response.headers, body: bytes, json: parsed });
      });
    });
    req.on('error', reject);
    if (body) req.end(JSON.stringify(body)); else req.end();
  });
}

async function waitForReady(id) {
  for (let i = 0; i < 30; i++) {
    const response = await request('GET', `/v1/jobs/${id}`, null, 'smoke-token');
    assert.equal(response.status, 200);
    if (response.json.status === 'ready' || response.json.status === 'error') return response.json;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error('job did not finish');
}

(async () => {
  try {
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('resolver startup timed out')), 5000);
      child.stdout.on('data', (chunk) => { if (chunk.toString().includes('listening')) { clearTimeout(timeout); resolve(); } });
      child.on('error', reject);
    });
    const health = await request('GET', '/health', null, 'smoke-token');
    assert.equal(health.status, 200);
    assert.equal(health.json.ok, true);
    const badHealth = await request('GET', '/health', null, 'wrong-token');
    assert.equal(badHealth.status, 401);
    const unauthenticated = await request('POST', '/v1/jobs', { url: 'https://www.youtube.com/watch?v=smoke' }, 'wrong-token');
    assert.equal(unauthenticated.status, 401);
    const created = await request('POST', '/v1/jobs', { url: 'https://www.youtube.com/watch?v=smoke' }, 'smoke-token');
    assert.equal(created.status, 202);
    const job = created.json;
    const ready = await waitForReady(job.id);
    assert.equal(ready.status, 'ready');
    const file = await request('GET', `/v1/jobs/${job.id}/file`, null, 'smoke-token');
    assert.equal(file.status, 200);
    assert.equal(file.body.toString('utf8'), 'fake-video');
    const facebook = await request('POST', '/v1/jobs', { url: 'https://www.facebook.com/share/v/1QRaD6gwyz/' }, 'smoke-token');
    assert.equal(facebook.status, 202);
    const facebookReady = await waitForReady(facebook.json.id);
    assert.equal(facebookReady.status, 'ready');
    const badHost = await request('POST', '/v1/jobs', { url: 'https://unapproved.example/video.mp4' }, 'smoke-token');
    assert.equal(badHost.status, 400);
    console.log('Resolver smoke test passed.');
  } finally {
    child.kill();
    fs.rmSync(jobDir, { recursive: true, force: true });
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
