'use strict';

const http = require('node:http');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { pipeline } = require('node:stream/promises');
const { Transform } = require('node:stream');
const execFileAsync = promisify(execFile);

const ROOT = __dirname;
const PUBLIC_DIR = path.join(ROOT, 'public');
const SEED_FILE = path.join(ROOT, 'seed-apps.json');
const MAX_JSON_BYTES = 64 * 1024;
const MAX_APK_BYTES = 300 * 1024 * 1024;
const CATEGORIES = new Set(['Tools', 'Video & Audio', 'Productivity', 'Photo & Design', 'Education', 'Games', 'Lifestyle', 'Other']);
const MIME_TYPES = { '.css': 'text/css; charset=utf-8', '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.webp': 'image/webp' };

function httpError(status, message, headers = undefined) {
  const error = new Error(message);
  error.status = status;
  error.headers = headers;
  return error;
}

function publicApp(app) {
  return {
    slug: app.slug, name: app.name, tagline: app.tagline, description: app.description,
    category: app.category, version: app.version, platform: 'Android', developerName: app.developerName,
    iconUrl: app.iconUrl, sizeBytes: app.sizeBytes || 0, downloads: app.downloads || 0,
    published: !!app.published, status: app.status || (app.published ? 'approved' : 'draft'), createdAt: app.createdAt
  };
}

function developerApp(app) {
  return { ...publicApp(app), dailyDownloads: app.dailyDownloads || {}, reviewNote: app.reviewNote || '' };
}

function adminApp(app) {
  return { ...developerApp(app), ownerEmail: app.ownerEmail || null };
}

function parseCookies(request) {
  const result = {};
  for (const part of (request.headers.cookie || '').split(';')) {
    const index = part.indexOf('=');
    if (index < 0) continue;
    const key = part.slice(0, index).trim();
    try { result[key] = decodeURIComponent(part.slice(index + 1).trim()); } catch {}
  }
  return result;
}

function makeSlug(name) {
  const base = name.toLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 42) || 'app';
  return `${base}-${crypto.randomBytes(3).toString('hex')}`;
}

function normalizeEmail(value) {
  return String(value || '').trim().toLowerCase();
}

async function isAndroidPackage(filename) {
  const file = await fsp.open(filename, 'r');
  try {
    const info = await file.stat();
    if (info.size < 22) return false;
    const first = Buffer.alloc(4);
    if ((await file.read(first, 0, 4, 0)).bytesRead !== 4 || first.readUInt32LE(0) !== 0x04034b50) return false;

    const tailLength = Math.min(info.size, 22 + 0xffff);
    const tail = Buffer.alloc(tailLength);
    await file.read(tail, 0, tailLength, info.size - tailLength);
    let eocd = -1;
    for (let offset = tail.length - 22; offset >= 0; offset--) {
      if (tail.readUInt32LE(offset) === 0x06054b50) { eocd = offset; break; }
    }
    if (eocd < 0 || tail.readUInt16LE(eocd + 4) !== 0 || tail.readUInt16LE(eocd + 6) !== 0) return false;
    const diskEntries = tail.readUInt16LE(eocd + 8);
    const entries = tail.readUInt16LE(eocd + 10);
    const directorySize = tail.readUInt32LE(eocd + 12);
    const directoryOffset = tail.readUInt32LE(eocd + 16);
    const commentLength = tail.readUInt16LE(eocd + 20);
    const absoluteEocd = info.size - tailLength + eocd;
    if (!entries || entries !== diskEntries || entries === 0xffff || directorySize === 0xffffffff || directoryOffset === 0xffffffff || eocd + 22 + commentLength !== tail.length || directorySize > 64 * 1024 * 1024 || directoryOffset + directorySize > absoluteEocd) return false;

    const directory = Buffer.alloc(directorySize);
    if ((await file.read(directory, 0, directorySize, directoryOffset)).bytesRead !== directorySize) return false;
    const names = [];
    let position = 0;
    for (let index = 0; index < entries; index++) {
      if (position + 46 > directory.length || directory.readUInt32LE(position) !== 0x02014b50) return false;
      const nameLength = directory.readUInt16LE(position + 28);
      const extraLength = directory.readUInt16LE(position + 30);
      const entryCommentLength = directory.readUInt16LE(position + 32);
      const end = position + 46 + nameLength + extraLength + entryCommentLength;
      if (!nameLength || end > directory.length) return false;
      names.push(directory.toString('utf8', position + 46, position + 46 + nameLength));
      position = end;
    }
    if (position !== directory.length || !names.includes('AndroidManifest.xml') || !names.some((name) => /^classes(?:\d+)?\.dex$/.test(name))) return false;

    const hasV1Signing = names.some((name) => /^META-INF\/[^/]+\.SF$/i.test(name)) && names.some((name) => /^META-INF\/[^/]+\.(?:RSA|DSA|EC)$/i.test(name));
    let hasV2Signing = false;
    if (directoryOffset >= 24) {
      const footer = Buffer.alloc(24);
      await file.read(footer, 0, footer.length, directoryOffset - footer.length);
      if (footer.subarray(8).toString('ascii') === 'APK Sig Block 42') {
        const blockSize = footer.readBigUInt64LE(0);
        const blockStart = BigInt(directoryOffset) - blockSize - 8n;
        if (blockSize >= 24n && blockStart >= 0n && blockSize <= BigInt(Number.MAX_SAFE_INTEGER)) {
          const leadingSize = Buffer.alloc(8);
          await file.read(leadingSize, 0, 8, Number(blockStart));
          hasV2Signing = leadingSize.readBigUInt64LE(0) === blockSize;
        }
      }
    }
    return hasV1Signing || hasV2Signing;
  } finally { await file.close(); }
}

function validAppInput(body) {
  const name = String(body.name || '').trim();
  const tagline = String(body.tagline || '').trim();
  const description = String(body.description || '').trim();
  const category = String(body.category || '').trim();
  const version = String(body.version || '').trim();
  const iconUrl = String(body.iconUrl || '').trim();
  if (name.length < 2 || name.length > 60) throw httpError(400, 'App name must be between 2 and 60 characters.');
  if (tagline.length < 8 || tagline.length > 110) throw httpError(400, 'Short description must be between 8 and 110 characters.');
  if (description.length < 20 || description.length > 3000) throw httpError(400, 'App description must be between 20 and 3,000 characters.');
  if (!CATEGORIES.has(category)) throw httpError(400, 'Choose a supported app category.');
  if (!/^[0-9A-Za-z][0-9A-Za-z.+_-]{0,19}$/.test(version)) throw httpError(400, 'Enter a valid version number.');
  if (iconUrl && (!/^https:\/\//i.test(iconUrl) || iconUrl.length > 500)) throw httpError(400, 'App icon must use a valid HTTPS URL.');
  return { name, tagline, description, category, version, iconUrl: iconUrl || '/assets/tata-mark.svg' };
}

async function verifyApkSignature(filename, options) {
  if (!options.requireApkSigner && !options.apksignerPath) return;
  if (!options.apksignerPath) throw httpError(503, 'APK signature verification is required but apksigner is not configured on this server.');
  let output;
  try {
    const result = await execFileAsync(options.apksignerPath, ['verify', '--verbose', '--print-certs', filename], {
      timeout: 60_000,
      maxBuffer: 2 * 1024 * 1024,
      windowsHide: true,
    });
    output = `${result.stdout || ''}\n${result.stderr || ''}`;
  } catch (error) {
    if (error.code === 'ENOENT') throw httpError(503, 'APK signature verification is required but apksigner was not found on this server.');
    throw httpError(400, 'The APK signature could not be verified. Upload a valid release-signed APK.');
  }
  if (!/Verified using (?:v[123](?:\.1)? scheme|v[123](?:\.1)? scheme \(APK Signature Scheme v[123](?:\.1)?\)): true/i.test(output)) {
    throw httpError(400, 'The APK is not signed with a supported Android APK signature scheme.');
  }
  if (options.allowedCertDigests.length) {
    const found = [...output.matchAll(/certificate SHA-256 digest:\s*([a-f0-9:]+)/ig)].map((match) => match[1].toLowerCase());
    if (!found.some((digest) => options.allowedCertDigests.includes(digest))) {
      throw httpError(400, 'This APK is signed by an unapproved certificate.');
    }
  }
}

function createStoreServer(options = {}) {
  const storageDir = path.resolve(options.storageDir || process.env.TATA_STORE_STORAGE || path.join(ROOT, 'storage'));
  const uploadsDir = path.join(storageDir, 'packages');
  const databaseFile = path.join(storageDir, 'store.json');
  const seedFile = options.seedFile || SEED_FILE;
  const secureCookie = options.secureCookie ?? process.env.NODE_ENV === 'production';
  const adminEmail = normalizeEmail(options.adminEmail ?? process.env.TATA_STORE_ADMIN_EMAIL);
  const adminSetupToken = String(options.adminSetupToken ?? process.env.TATA_STORE_ADMIN_SETUP_TOKEN ?? '');
  const apksignerPath = String(options.apksignerPath ?? process.env.TATA_STORE_APKSIGNER ?? '').trim();
  const requireApkSigner = options.requireApkSigner ?? (process.env.TATA_STORE_REQUIRE_APKSIGNER === 'true' || process.env.NODE_ENV === 'production');
  const allowedCertDigests = String(options.allowedCertDigests ?? process.env.TATA_STORE_ALLOWED_CERT_SHA256 ?? '')
    .split(',').map((value) => value.trim().toLowerCase()).filter((value) => /^[a-f0-9:]{32,95}$/.test(value));
  let database;
  let writeQueue = Promise.resolve();
  const sessions = new Map();
  const limits = new Map();

  async function initialize() {
    await fsp.mkdir(uploadsDir, { recursive: true });
    const seeds = JSON.parse(await fsp.readFile(seedFile, 'utf8'));
    try { database = JSON.parse(await fsp.readFile(databaseFile, 'utf8')); }
    catch (error) {
      if (error.code !== 'ENOENT') throw error;
      database = { users: [], apps: seeds, reports: [] };
      await save();
    }
    database.users ||= [];
    database.apps ||= [];
    database.reports ||= [];
    for (const seed of seeds) {
      if (!database.apps.some((app) => app.id === seed.id)) database.apps.push(seed);
    }
    await save();
  }

  function save() {
    const snapshot = JSON.stringify(database, null, 2);
    writeQueue = writeQueue.catch(() => {}).then(async () => {
      const temporary = `${databaseFile}.${process.pid}.${crypto.randomBytes(4).toString('hex')}.tmp`;
      await fsp.writeFile(temporary, snapshot, { mode: 0o600 });
      await fsp.rename(temporary, databaseFile);
    });
    return writeQueue;
  }

  function json(response, status, body, extraHeaders = {}) {
    const encoded = Buffer.from(JSON.stringify(body));
    response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': encoded.length, 'Cache-Control': 'no-store', ...extraHeaders });
    response.end(encoded);
  }

  async function readJson(request) {
    const type = String(request.headers['content-type'] || '');
    if (!type.toLowerCase().startsWith('application/json')) throw httpError(415, 'Send this request as JSON.');
    const chunks = [];
    let size = 0;
    for await (const chunk of request) {
      size += chunk.length;
      if (size > MAX_JSON_BYTES) throw httpError(413, 'Request body is too large.');
      chunks.push(chunk);
    }
    try {
      const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw httpError(400, 'Send a JSON object.');
      return value;
    }
    catch { throw httpError(400, 'The request body is not valid JSON.'); }
  }

  async function currentUser(request, required = false) {
    const token = parseCookies(request).tata_session;
    const session = token && sessions.get(token);
    if (!session || session.expiresAt < Date.now()) {
      if (token) sessions.delete(token);
      if (required) throw httpError(401, 'Sign in to continue.');
      return null;
    }
    const user = database.users.find((entry) => entry.id === session.userId);
    if (!user && required) throw httpError(401, 'Sign in to continue.');
    return user || null;
  }

  function sessionCookie(token, maxAge) {
    return `tata_session=${encodeURIComponent(token)}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${maxAge}${secureCookie ? '; Secure' : ''}`;
  }

  function issueSession(response, user) {
    const token = crypto.randomBytes(32).toString('base64url');
    const expiresAt = Date.now() + 7 * 24 * 60 * 60 * 1000;
    sessions.set(token, { userId: user.id, expiresAt });
    response.setHeader('Set-Cookie', sessionCookie(token, 7 * 24 * 60 * 60));
  }

  function rateLimit(request, key, maximum = 12, windowMs = 60_000) {
    const now = Date.now();
    const identity = `${key}:${request.socket.remoteAddress || 'local'}`;
    const entry = limits.get(identity);
    if (!entry || entry.resetAt <= now) limits.set(identity, { count: 1, resetAt: now + windowMs });
    else if (++entry.count > maximum) throw httpError(429, 'Too many attempts. Please wait a minute and try again.');
    if (limits.size > 3000) for (const [item, value] of limits) if (value.resetAt <= now) limits.delete(item);
  }

  function ownedApp(user, slug) {
    const app = database.apps.find((entry) => entry.slug === slug && entry.ownerId === user.id);
    if (!app) throw httpError(404, 'App not found in your developer account.');
    return app;
  }

  async function handleApi(request, response, url) {
    if (['POST', 'PATCH', 'DELETE'].includes(request.method)) {
      const origin = request.headers.origin;
      if (origin) {
        const expectedHttp = `http://${request.headers.host}`;
        const expectedHttps = `https://${request.headers.host}`;
        if (origin !== expectedHttp && origin !== expectedHttps) throw httpError(403, 'Cross-site requests are not allowed.');
      }
    }
    const pathname = decodeURIComponent(url.pathname);

    if (request.method === 'GET' && pathname === '/api/health') return json(response, 200, { ok: true, service: 'tata-store' });

    if (request.method === 'GET' && pathname === '/api/apps') {
      const search = (url.searchParams.get('q') || '').trim().toLowerCase();
      const rawCategory = url.searchParams.get('category');
      const category = rawCategory && CATEGORIES.has(rawCategory) ? rawCategory : undefined;
      const page = Math.max(1, Number.parseInt(url.searchParams.get('page') || '1', 10) || 1);
      const limit = Math.max(1, Math.min(48, Number.parseInt(url.searchParams.get('limit') || '24', 10) || 24));
      const sort = url.searchParams.get('sort');
      const all = database.apps.filter((app) => app.published && app.status !== 'pending' && app.status !== 'rejected' && (!category || app.category === category) && (!search || [app.name, app.tagline, app.description, app.developerName, app.category].join(' ').toLowerCase().includes(search)));
      if (sort === 'popular') all.sort((a, b) => (b.downloads || 0) - (a.downloads || 0));
      else if (sort === 'newest') all.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)));
      else if (sort === 'name') all.sort((a, b) => a.name.localeCompare(b.name));
      else all.sort((a, b) => Number(!!b.packageUrl) - Number(!!a.packageUrl) || String(b.createdAt).localeCompare(String(a.createdAt)));
      const start = (page - 1) * limit;
      const apps = all.slice(start, start + limit).map(publicApp);
      return json(response, 200, { apps, total: all.length, page, hasMore: start + apps.length < all.length });
    }

    const reportMatch = pathname.match(/^\/api\/apps\/([a-z0-9-]+)\/report$/);
    if (request.method === 'POST' && reportMatch) {
      rateLimit(request, 'report-app', 5, 60 * 60 * 1000);
      const app = database.apps.find((entry) => entry.slug === reportMatch[1] && entry.published && entry.status === 'approved');
      if (!app) throw httpError(404, 'This app is not available.');
      const body = await readJson(request);
      const message = String(body.message || '').trim();
      const email = normalizeEmail(body.email);
      if (message.length < 20 || message.length > 1500) throw httpError(400, 'Describe the issue in 20 to 1,500 characters.');
      if (email && (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254)) throw httpError(400, 'Enter a valid contact email address.');
      database.reports.push({ id: crypto.randomUUID(), appId: app.id, slug: app.slug, appName: app.name, email: email || null, message, createdAt: new Date().toISOString(), status: 'open' });
      await save();
      return json(response, 202, { ok: true });
    }

    const detailMatch = pathname.match(/^\/api\/apps\/([a-z0-9-]+)$/);
    if (request.method === 'GET' && detailMatch) {
      const app = database.apps.find((entry) => entry.slug === detailMatch[1] && entry.published && entry.status !== 'pending' && entry.status !== 'rejected');
      if (!app) throw httpError(404, 'This app is not available.');
      return json(response, 200, { app: publicApp(app) });
    }

    const downloadMatch = pathname.match(/^\/api\/apps\/([a-z0-9-]+)\/download$/);
    if (request.method === 'GET' && downloadMatch) {
      const app = database.apps.find((entry) => entry.slug === downloadMatch[1] && entry.published && entry.status !== 'pending' && entry.status !== 'rejected');
      if (!app) throw httpError(404, 'This app is not available.');
      app.downloads = (app.downloads || 0) + 1;
      const day = new Date().toISOString().slice(0, 10);
      app.dailyDownloads ||= {};
      app.dailyDownloads[day] = (app.dailyDownloads[day] || 0) + 1;
      await save();
      if (app.packageUrl) {
        const external = new URL(app.packageUrl);
        if (external.protocol !== 'https:') throw httpError(500, 'App download is not configured safely.');
        response.writeHead(302, { Location: external.href, 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' });
        return response.end();
      }
      const packageKey = app.packageKey || `${app.id}.apk`;
      if (!/^[a-f0-9-]+\.apk$/i.test(packageKey)) throw httpError(500, 'App package is not configured safely.');
      const packagePath = path.join(uploadsDir, packageKey);
      const info = await fsp.stat(packagePath).catch(() => null);
      if (!info?.isFile()) throw httpError(404, 'The app package is temporarily unavailable.');
      const range = request.headers.range;
      let start = 0;
      let end = info.size - 1;
      let status = 200;
      if (range) {
        const match = /^bytes=(\d*)-(\d*)$/.exec(range);
        if (!match || (!match[1] && !match[2])) throw httpError(416, 'Requested byte range is invalid.');
        if (!match[1]) { const suffix = Number(match[2]); start = Math.max(0, info.size - suffix); }
        else start = Number(match[1]);
        if (match[2] && match[1]) end = Math.min(info.size - 1, Number(match[2]));
        if (start >= info.size || end < start) throw httpError(416, 'Requested byte range is unavailable.', { 'Content-Range': `bytes */${info.size}` });
        status = 206;
      }
      const headers = {
        'Content-Type': 'application/vnd.android.package-archive', 'Content-Length': end - start + 1,
        'Content-Disposition': `attachment; filename="${app.slug}.apk"`, 'Accept-Ranges': 'bytes',
        'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'
      };
      if (status === 206) headers['Content-Range'] = `bytes ${start}-${end}/${info.size}`;
      response.writeHead(status, headers);
      return pipeline(fs.createReadStream(packagePath, { start, end }), response);
    }

    if (request.method === 'POST' && pathname === '/api/auth/signup') {
      rateLimit(request, 'signup', 6, 60 * 60 * 1000);
      const body = await readJson(request);
      const email = normalizeEmail(body.email);
      const password = String(body.password || '');
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254) throw httpError(400, 'Enter a valid email address.');
      if (password.length < 12 || password.length > 200) throw httpError(400, 'Use a password between 12 and 200 characters.');
      const displayName = String(body.displayName || '').trim();
      if (displayName.length < 2 || displayName.length > 50) throw httpError(400, 'Studio name must be between 2 and 50 characters.');
      if (database.users.some((user) => user.email === email)) throw httpError(409, 'An account with this email already exists.');
      const isAdmin = !!adminEmail && email === adminEmail;
      if (isAdmin) {
        const suppliedToken = String(body.adminSetupToken || '');
        const expected = Buffer.from(adminSetupToken);
        const supplied = Buffer.from(suppliedToken);
        if (expected.length < 32) throw httpError(503, 'Store administrator setup is not configured. Set a private TATA_STORE_ADMIN_SETUP_TOKEN first.');
        if (supplied.length !== expected.length || !crypto.timingSafeEqual(supplied, expected)) throw httpError(403, 'The store setup key is incorrect.');
      }
      const salt = crypto.randomBytes(16);
      const passwordHash = await new Promise((resolve, reject) => crypto.scrypt(password, salt, 64, (error, value) => error ? reject(error) : resolve(value.toString('hex'))));
      if (database.users.some((user) => user.email === email)) throw httpError(409, 'An account with this email already exists.');
      const user = { id: crypto.randomUUID(), email, displayName, role: isAdmin ? 'admin' : 'developer', salt: salt.toString('hex'), passwordHash, createdAt: new Date().toISOString() };
      database.users.push(user);
      await save();
      issueSession(response, user);
      return json(response, 201, { user: { email: user.email, displayName: user.displayName, role: user.role } });
    }

    if (request.method === 'POST' && pathname === '/api/auth/login') {
      rateLimit(request, 'login');
      const body = await readJson(request);
      const email = normalizeEmail(body.email);
      const password = String(body.password || '');
      if (password.length > 200) throw httpError(401, 'Email or password is incorrect.');
      const user = database.users.find((entry) => entry.email === email);
      const salt = user ? Buffer.from(user.salt, 'hex') : Buffer.alloc(16);
      const expected = user ? Buffer.from(user.passwordHash, 'hex') : Buffer.alloc(64);
      const actual = await new Promise((resolve, reject) => crypto.scrypt(password.slice(0, 200), salt, 64, (error, value) => error ? reject(error) : resolve(value)));
      if (!user || !crypto.timingSafeEqual(actual, expected)) throw httpError(401, 'Email or password is incorrect.');
      issueSession(response, user);
      return json(response, 200, { user: { email: user.email, displayName: user.displayName || user.email.split('@')[0], role: user.role || 'developer' } });
    }

    if (request.method === 'POST' && pathname === '/api/auth/logout') {
      const token = parseCookies(request).tata_session;
      if (token) sessions.delete(token);
      response.setHeader('Set-Cookie', sessionCookie('', 0));
      return json(response, 200, { ok: true });
    }

    if (request.method === 'GET' && pathname === '/api/me') {
      const user = await currentUser(request, true);
      return json(response, 200, { user: { email: user.email, displayName: user.displayName || user.email.split('@')[0], role: user.role || 'developer' } });
    }

    if (request.method === 'POST' && pathname === '/api/admin/claim') {
      const user = await currentUser(request, true);
      const body = await readJson(request);
      const expected = Buffer.from(adminSetupToken);
      const supplied = Buffer.from(String(body.setupToken || ''));
      if (!adminEmail || user.email !== adminEmail) throw httpError(403, 'This email is not configured as the store administrator.');
      if (expected.length < 32) throw httpError(503, 'Store administrator setup is not configured.');
      if (supplied.length !== expected.length || !crypto.timingSafeEqual(supplied, expected)) throw httpError(403, 'The store setup key is incorrect.');
      user.role = 'admin';
      await save();
      return json(response, 200, { user: { email: user.email, displayName: user.displayName || user.email.split('@')[0], role: user.role } });
    }

    if (pathname.startsWith('/api/admin/')) {
      const user = await currentUser(request, true);
      if (user.role !== 'admin' || !adminEmail || user.email !== adminEmail) throw httpError(403, 'This action is restricted to the store administrator.');
      if (request.method === 'GET' && pathname === '/api/admin/apps') {
        return json(response, 200, { apps: database.apps.filter((app) => app.status === 'pending').map(adminApp) });
      }
      if (request.method === 'GET' && pathname === '/api/admin/reports') {
        return json(response, 200, { reports: database.reports.filter((report) => report.status === 'open').slice(-500).reverse() });
      }
      const reviewMatch = pathname.match(/^\/api\/admin\/apps\/([a-z0-9-]+)\/review$/);
      if (request.method === 'PATCH' && reviewMatch) {
        const body = await readJson(request);
        const app = database.apps.find((entry) => entry.slug === reviewMatch[1]);
        if (!app) throw httpError(404, 'App not found.');
        if (!['approved', 'rejected'].includes(body.status)) throw httpError(400, 'Choose approved or rejected.');
        app.status = body.status;
        app.published = body.status === 'approved';
        app.reviewedAt = new Date().toISOString();
        app.reviewNote = String(body.note || '').trim().slice(0, 1000);
        await save();
        return json(response, 200, { app: adminApp(app) });
      }
      const reportReview = pathname.match(/^\/api\/admin\/reports\/([a-f0-9-]+)$/i);
      if (request.method === 'PATCH' && reportReview) {
        const body = await readJson(request);
        const report = database.reports.find((entry) => entry.id === reportReview[1]);
        if (!report) throw httpError(404, 'Report not found.');
        if (!['resolved', 'dismissed'].includes(body.status)) throw httpError(400, 'Choose resolved or dismissed.');
        report.status = body.status;
        report.resolvedAt = new Date().toISOString();
        await save();
        return json(response, 200, { ok: true });
      }
      throw httpError(404, 'Not found.');
    }

    if (request.method === 'DELETE' && pathname === '/api/me') {
      const user = await currentUser(request, true);
      const owned = database.apps.filter((app) => app.ownerId === user.id);
      database.apps = database.apps.filter((app) => app.ownerId !== user.id);
      database.users = database.users.filter((entry) => entry.id !== user.id);
      await save();
      for (const app of owned) if (app.packageKey && /^[a-f0-9-]+\.apk$/i.test(app.packageKey)) await fsp.rm(path.join(uploadsDir, app.packageKey), { force: true });
      for (const [token, session] of sessions) if (session.userId === user.id) sessions.delete(token);
      response.setHeader('Set-Cookie', sessionCookie('', 0));
      return json(response, 200, { ok: true });
    }

    if (request.method === 'GET' && pathname === '/api/developer/apps') {
      const user = await currentUser(request, true);
      const apps = database.apps.filter((app) => app.ownerId === user.id).map(developerApp);
      return json(response, 200, { apps });
    }

    if (request.method === 'POST' && pathname === '/api/developer/apps') {
      rateLimit(request, 'create-app', 20, 60 * 60 * 1000);
      const user = await currentUser(request, true);
      const input = validAppInput(await readJson(request));
      const app = {
        id: crypto.randomUUID(), slug: makeSlug(input.name), ...input, platform: 'Android',
        developerName: String(user.displayName || user.email.split('@')[0]).slice(0, 50), ownerId: user.id,
        sizeBytes: 0, downloads: 0, dailyDownloads: {}, published: false, status: 'draft', ownerEmail: user.email,
        createdAt: new Date().toISOString()
      };
      database.apps.push(app);
      await save();
      return json(response, 201, { app: publicApp(app) });
    }

    const updateMatch = pathname.match(/^\/api\/developer\/apps\/([a-z0-9-]+)$/);
    if (request.method === 'PATCH' && updateMatch) {
      const user = await currentUser(request, true);
      const app = ownedApp(user, updateMatch[1]);
      const input = validAppInput(await readJson(request));
      if (input.version !== app.version) throw httpError(400, 'Upload the new APK to change an app version.');
      Object.assign(app, input);
      if (app.packageKey && app.status === 'rejected') {
        app.status = 'pending';
        app.published = false;
        app.reviewNote = '';
      }
      await save();
      return json(response, 200, { app: publicApp(app) });
    }

    const packageMatch = pathname.match(/^\/api\/developer\/apps\/([a-z0-9-]+)\/package$/);
    if (request.method === 'POST' && packageMatch) {
      rateLimit(request, 'upload-package', 10, 60 * 60 * 1000);
      const user = await currentUser(request, true);
      const app = ownedApp(user, packageMatch[1]);
      const origin = request.headers.origin;
      const contentType = String(request.headers['content-type'] || '').toLowerCase();
      if (!['application/vnd.android.package-archive', 'application/octet-stream'].includes(contentType)) throw httpError(415, 'Upload an Android APK package.');
      const nextVersion = String(request.headers['x-store-version'] || app.version).trim();
      if (!/^[0-9A-Za-z][0-9A-Za-z.+_-]{0,19}$/.test(nextVersion)) throw httpError(400, 'Enter a valid version number.');
      if (Number(request.headers['content-length'] || 0) > MAX_APK_BYTES) throw httpError(413, 'APK packages must be 300 MB or smaller.');
      const temporary = path.join(uploadsDir, `${app.id}.${crypto.randomBytes(6).toString('hex')}.upload`);
      let bytes = 0;
      let previousPackageKey;
      let storedPackagePath;
      let previousPackageState;
      try {
        const limiter = new Transform({ transform(chunk, encoding, callback) {
          bytes += chunk.length;
          if (bytes > MAX_APK_BYTES) return callback(httpError(413, 'APK packages must be 300 MB or smaller.'));
          callback(null, chunk);
        } });
        await pipeline(request, limiter, fs.createWriteStream(temporary, { flags: 'wx', mode: 0o600 }));
        if (!await isAndroidPackage(temporary)) throw httpError(400, 'This file is not an installable Android package. Include AndroidManifest.xml, DEX code, and APK signing metadata.');
        await verifyApkSignature(temporary, { apksignerPath, requireApkSigner, allowedCertDigests });
        previousPackageKey = app.packageKey;
        previousPackageState = { packageKey: app.packageKey, version: app.version, sizeBytes: app.sizeBytes, published: app.published, status: app.status };
        const packageKey = `${app.id}-${crypto.randomUUID()}.apk`;
        storedPackagePath = path.join(uploadsDir, packageKey);
        await fsp.rename(temporary, storedPackagePath);
        app.packageKey = packageKey;
        app.version = nextVersion;
        app.sizeBytes = bytes;
        app.published = false;
        app.status = 'pending';
        await save();
      } catch (error) {
        if (previousPackageState) Object.assign(app, previousPackageState);
        await fsp.rm(temporary, { force: true }).catch(() => {});
        if (storedPackagePath) await fsp.rm(storedPackagePath, { force: true }).catch(() => {});
        throw error;
      }
      if (previousPackageKey && previousPackageKey !== app.packageKey && /^[a-f0-9-]+\.apk$/i.test(previousPackageKey)) await fsp.rm(path.join(uploadsDir, previousPackageKey), { force: true }).catch(() => {});
      return json(response, 200, { app: publicApp(app) });
    }

    const deleteMatch = pathname.match(/^\/api\/developer\/apps\/([a-z0-9-]+)$/);
    if (request.method === 'DELETE' && deleteMatch) {
      const user = await currentUser(request, true);
      const app = ownedApp(user, deleteMatch[1]);
      database.apps = database.apps.filter((entry) => entry.id !== app.id);
      await save();
      if (app.packageKey) await fsp.rm(path.join(uploadsDir, app.packageKey), { force: true });
      return json(response, 200, { ok: true });
    }

    return json(response, 404, { error: 'Not found.' });
  }

  async function serveStatic(request, response, pathname) {
    const decoded = decodeURIComponent(pathname);
    const relative = decoded === '/' || /^\/apps\/[a-z0-9-]+$/.test(decoded) ? 'index.html' : decoded === '/privacy' ? 'privacy.html' : decoded === '/terms' ? 'terms.html' : decoded.replace(/^\/+/, '');
    const target = path.resolve(PUBLIC_DIR, relative);
    if (target !== PUBLIC_DIR && !target.startsWith(PUBLIC_DIR + path.sep)) throw httpError(404, 'Not found.');
    const info = await fsp.stat(target).catch(() => null);
    if (!info?.isFile()) throw httpError(404, 'Not found.');
    response.writeHead(200, {
      'Content-Type': MIME_TYPES[path.extname(target).toLowerCase()] || 'application/octet-stream',
      'Content-Length': info.size, 'Cache-Control': path.basename(target) === 'index.html' ? 'no-cache' : 'public, max-age=3600',
      'X-Content-Type-Options': 'nosniff'
    });
    if (request.method === 'HEAD') return response.end();
    return pipeline(fs.createReadStream(target), response);
  }

  const server = http.createServer(async (request, response) => {
    response.setHeader('X-Frame-Options', 'DENY');
    response.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
    response.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
    response.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data: https:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'");
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
    if (secureCookie) response.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
    if (!['GET', 'HEAD', 'POST', 'PATCH', 'DELETE'].includes(request.method)) return json(response, 405, { error: 'Method not allowed.' }, { Allow: 'GET, HEAD, POST, PATCH, DELETE' });
    try {
      const url = new URL(request.url, 'http://tata-store.local');
      if (url.pathname.startsWith('/api/')) {
        if (request.method === 'HEAD') return json(response, 405, { error: 'Method not allowed.' });
        return await handleApi(request, response, url);
      }
      if (request.method === 'POST' || request.method === 'PATCH' || request.method === 'DELETE') return json(response, 404, { error: 'Not found.' });
      return await serveStatic(request, response, url.pathname);
    } catch (error) {
      if (!response.headersSent) json(response, error.status || 500, { error: error.status ? error.message : 'The store could not complete this request.' }, error.headers || {});
      else response.destroy(error);
      if (!error.status) console.error('Tata Store request failed:', error);
    }
  });

  server.requestTimeout = 10 * 60 * 1000;
  server.headersTimeout = 30_000;
  server.keepAliveTimeout = 5_000;
  return { server, initialize };
}

if (require.main === module) {
  const { server, initialize } = createStoreServer();
  initialize().then(() => {
    const host = process.env.HOST || '127.0.0.1';
    const port = Number(process.env.PORT || 8840);
    server.listen(port, host, () => console.log(`Tata Store is ready at http://${host}:${server.address().port}`));
  }).catch((error) => { console.error('Could not start Tata Store:', error); process.exitCode = 1; });
}

module.exports = { createStoreServer };
