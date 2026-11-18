'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { createStoreServer } = require('../server.js');

function makeApk(entries) {
  const localParts = [];
  const centralParts = [];
  let localOffset = 0;
  for (const [name, contents] of entries) {
    const filename = Buffer.from(name);
    const data = Buffer.from(contents);
    const local = Buffer.alloc(30 + filename.length);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(filename.length, 26); filename.copy(local, 30);
    localParts.push(local, data);
    const central = Buffer.alloc(46 + filename.length);
    central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6);
    central.writeUInt32LE(data.length, 20); central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(filename.length, 28); central.writeUInt32LE(localOffset, 42); filename.copy(central, 46);
    centralParts.push(central);
    localOffset += local.length + data.length;
  }
  const centralDirectory = Buffer.concat(centralParts);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralDirectory.length, 12); end.writeUInt32LE(localOffset, 16);
  return Buffer.concat([...localParts, centralDirectory, end]);
}

function addV2SigningBlock(zip) {
  const eocdOffset = zip.length - 22;
  const directoryOffset = zip.readUInt32LE(eocdOffset + 16);
  const size = Buffer.alloc(8); size.writeBigUInt64LE(40n);
  const pairSize = Buffer.alloc(8); pairSize.writeBigUInt64LE(8n);
  const id = Buffer.alloc(4); id.writeUInt32LE(0x7109871a);
  const magic = Buffer.from('APK Sig Block 42', 'ascii');
  const block = Buffer.concat([size, pairSize, id, Buffer.from([1, 2, 3, 4]), size, magic]);
  const output = Buffer.from(zip);
  output.writeUInt32LE(directoryOffset + block.length, eocdOffset + 16);
  return Buffer.concat([output.subarray(0, directoryOffset), block, output.subarray(directoryOffset)]);
}

test('store UI references existing elements and stays independent of inline event handlers', async () => {
  const html = await fs.readFile(path.join(__dirname, '..', 'public', 'index.html'), 'utf8');
  const script = await fs.readFile(path.join(__dirname, '..', 'public', 'app.js'), 'utf8');
  const dynamicIds = new Set(['reportAppButton', 'reportForm', 'reportMessage', 'shareAppButton']);
  for (const [, id] of script.matchAll(/\$\('([^']+)'\)/g)) if (!dynamicIds.has(id)) assert.match(html, new RegExp(`id=["']${id}["']`), `missing UI element ${id}`);
  assert.doesNotMatch(html, /\son[a-z]+=/i, 'inline event attributes must remain disabled by CSP');
  assert.match(html, /lang="en"/);
});

test('Tata Store serves Waslora and supports developer publishing and download analytics', async (t) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'tata-store-test-'));
  const seedFile = path.join(directory, 'seed.json');
  await fs.writeFile(seedFile, JSON.stringify([{
    id: 'app-waslora', slug: 'waslora', name: 'Waslora', tagline: 'Media, moving freely.',
    description: 'A useful Android media toolkit for downloads and file sharing.', category: 'Video & Audio',
    version: '0.4.1', platform: 'Android', developerName: 'Tata Studios', iconUrl: '/assets/tata-mark.svg',
    packageUrl: 'https://downloads.example/waslora.apk', sizeBytes: 1024, downloads: 0, dailyDownloads: {},
    published: true, status: 'approved', createdAt: new Date().toISOString()
  }]));
  const app = createStoreServer({ storageDir: path.join(directory, 'data'), seedFile, secureCookie: false, adminEmail: 'admin@example.test', adminSetupToken: 'a-secure-test-admin-setup-key-with-32-chars' });
  await app.initialize();
  await new Promise((resolve) => app.server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    app.server.closeAllConnections();
    await new Promise((resolve) => app.server.close(resolve));
    await fs.rm(directory, { recursive: true, force: true });
  });

  const origin = `http://127.0.0.1:${app.server.address().port}`;
  async function jsonRequest(route, { method = 'GET', body, cookie } = {}) {
    const response = await fetch(origin + route, {
      method, redirect: 'manual',
      headers: { ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(cookie ? { Cookie: cookie } : {}) },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {})
    });
    return { response, body: await response.json().catch(() => ({})) };
  }

  const home = await fetch(origin);
  assert.equal(home.status, 200);
  assert.match(await home.text(), /TATA[\s\S]*STORE/);
  assert.equal((await fetch(`${origin}/privacy`)).status, 200);
  assert.equal((await fetch(`${origin}/terms`)).status, 200);
  assert.equal((await fetch(`${origin}/apps/waslora`)).status, 200);

  const publicCatalog = await jsonRequest('/api/apps');
  assert.equal(publicCatalog.body.apps.length, 1);
  assert.equal(publicCatalog.body.apps[0].slug, 'waslora');
  assert.equal('ownerId' in publicCatalog.body.apps[0], false);
  assert.equal('dailyDownloads' in publicCatalog.body.apps[0], false, 'developer analytics must stay private');
  assert.equal((await jsonRequest('/api/apps/waslora')).body.app.slug, 'waslora');

  const noSession = await jsonRequest('/api/developer/apps', { method: 'POST', body: {} });
  assert.equal(noSession.response.status, 401);

  const invalidSignup = await jsonRequest('/api/auth/signup', { method: 'POST', body: { email: 'maker@example.test', password: 'short' } });
  assert.equal(invalidSignup.response.status, 400);

  const signup = await jsonRequest('/api/auth/signup', { method: 'POST', body: { email: 'Maker@example.test', displayName: 'Maker Studio', password: 'a-very-long-test-password' } });
  assert.equal(signup.response.status, 201);
  const cookie = signup.response.headers.get('set-cookie').split(';')[0];
  const session = await jsonRequest('/api/me', { cookie });
  assert.equal(session.body.user.email, 'maker@example.test');

  const created = await jsonRequest('/api/developer/apps', { method: 'POST', cookie, body: {
    name: 'Bright Notes', tagline: 'A calmer place for everyday notes.',
    description: 'Capture thoughts and keep your important notes organized in one simple Android app.',
    category: 'Productivity', version: '1.2.0'
  } });
  assert.equal(created.response.status, 201);
  const slug = created.body.app.slug;
  assert.equal(created.body.app.published, false);
  assert.equal(created.body.app.developerName, 'Maker Studio');
  assert.equal((await jsonRequest('/api/admin/apps', { cookie })).response.status, 403);
  assert.equal((await jsonRequest('/api/admin/claim', { method: 'POST', cookie, body: { setupToken: 'a-secure-test-admin-setup-key-with-32-chars' } })).response.status, 403);
  assert.equal((await jsonRequest('/api/apps')).body.apps.length, 1, 'an app without a package must stay private');

  const invalidPackage = await fetch(`${origin}/api/developer/apps/${slug}/package`, {
    method: 'POST', headers: { Cookie: cookie, 'Content-Type': 'application/vnd.android.package-archive' }, body: Buffer.from('not an APK')
  });
  assert.equal(invalidPackage.status, 400);
  const unsignedZip = makeApk([['AndroidManifest.xml', Buffer.from([1])], ['classes.dex', Buffer.from([2])]]);
  const unsignedUpload = await fetch(`${origin}/api/developer/apps/${slug}/package`, {
    method: 'POST', headers: { Cookie: cookie, 'Content-Type': 'application/vnd.android.package-archive' }, body: unsignedZip
  });
  assert.equal(unsignedUpload.status, 400, 'ZIP files without APK signing metadata must be rejected');
  const crossSiteUpload = await fetch(`${origin}/api/developer/apps/${slug}/package`, {
    method: 'POST', headers: { Cookie: cookie, Origin: 'https://attacker.example', 'Content-Type': 'application/vnd.android.package-archive' }, body: Buffer.from([0x50, 0x4b, 0x03, 0x04])
  });
  assert.equal(crossSiteUpload.status, 403);

  const packageBytes = makeApk([['AndroidManifest.xml', Buffer.from([0x01, 0x02])], ['classes.dex', Buffer.from([0x03, 0x04])], ['META-INF/CERT.SF', Buffer.from('digest')], ['META-INF/CERT.RSA', Buffer.from('signature')]]);
  const uploaded = await fetch(`${origin}/api/developer/apps/${slug}/package`, {
    method: 'POST', headers: { Cookie: cookie, 'Content-Type': 'application/vnd.android.package-archive' }, body: packageBytes
  });
  assert.equal(uploaded.status, 200);
  assert.equal((await uploaded.json()).app.published, false, 'uploaded APKs must wait for admin review');

  const deniedAdminSignup = await jsonRequest('/api/auth/signup', { method: 'POST', body: { email: 'admin@example.test', displayName: 'Store Team', password: 'another-very-long-password' } });
  assert.equal(deniedAdminSignup.response.status, 403, 'the store admin email must not be claimable without its private setup key');
  const adminSignup = await jsonRequest('/api/auth/signup', { method: 'POST', body: { email: 'admin@example.test', displayName: 'Store Team', password: 'another-very-long-password', adminSetupToken: 'a-secure-test-admin-setup-key-with-32-chars' } });
  assert.equal(adminSignup.response.status, 201);
  const adminCookie = adminSignup.response.headers.get('set-cookie').split(';')[0];
  assert.equal((await jsonRequest('/api/admin/claim', { method: 'POST', cookie: adminCookie, body: { setupToken: 'a-secure-test-admin-setup-key-with-32-chars' } })).body.user.role, 'admin');
  const queue = await jsonRequest('/api/admin/apps', { cookie: adminCookie });
  assert.equal(queue.body.apps.length, 1);
  assert.equal(queue.body.apps[0].ownerEmail, 'maker@example.test');
  const approved = await jsonRequest(`/api/admin/apps/${slug}/review`, { method: 'PATCH', cookie: adminCookie, body: { status: 'approved' } });
  assert.equal(approved.body.app.published, true);

  const listing = await jsonRequest('/api/apps');
  assert.equal(listing.body.apps.length, 2);
  const firstPage = await jsonRequest('/api/apps?limit=1');
  assert.equal(firstPage.body.total, 2);
  assert.equal(firstPage.body.hasMore, true);

  const badReport = await jsonRequest(`/api/apps/${slug}/report`, { method: 'POST', body: { message: 'bad' } });
  assert.equal(badReport.response.status, 400);
  const report = await jsonRequest(`/api/apps/${slug}/report`, { method: 'POST', body: { email: 'visitor@example.test', message: 'This app listing appears to use an icon without permission.' } });
  assert.equal(report.response.status, 202);
  assert.equal((await jsonRequest('/api/admin/reports', { cookie: adminCookie })).body.reports.length, 1);
  const reportId = (await jsonRequest('/api/admin/reports', { cookie: adminCookie })).body.reports[0].id;
  assert.equal((await jsonRequest(`/api/admin/reports/${reportId}`, { method: 'PATCH', cookie: adminCookie, body: { status: 'resolved' } })).response.status, 200);
  const range = await fetch(`${origin}/api/apps/${slug}/download`, { headers: { Range: 'bytes=0-5' }, redirect: 'manual' });
  assert.equal(range.status, 206);
  assert.equal(range.headers.get('content-range'), `bytes 0-5/${packageBytes.length}`);
  assert.deepEqual(Buffer.from(await range.arrayBuffer()), packageBytes.subarray(0, 6));

  const updated = await jsonRequest(`/api/developer/apps/${slug}`, { method: 'PATCH', cookie, body: {
    name: 'Bright Notes', tagline: 'A calmer home for notes and ideas.',
    description: 'Capture thoughts and keep your important notes organized in one simple Android app.',
    category: 'Productivity', version: '1.2.0'
  } });
  assert.equal(updated.response.status, 200);
  const nextPackageBytes = addV2SigningBlock(makeApk([['AndroidManifest.xml', Buffer.from([0x11])], ['classes.dex', Buffer.from([0x22])]]));
  const replaced = await fetch(`${origin}/api/developer/apps/${slug}/package`, {
    method: 'POST', headers: { Cookie: cookie, 'Content-Type': 'application/vnd.android.package-archive', 'X-Store-Version': '1.3.0' }, body: nextPackageBytes
  });
  assert.equal(replaced.status, 200);
  assert.equal((await (await jsonRequest('/api/admin/apps', { cookie: adminCookie })).body.apps).length, 1, 'an updated package must return to review');
  await jsonRequest(`/api/admin/apps/${slug}/review`, { method: 'PATCH', cookie: adminCookie, body: { status: 'approved' } });
  const replacedRange = await fetch(`${origin}/api/apps/${slug}/download`, { headers: { Range: 'bytes=0-7' }, redirect: 'manual' });
  assert.equal(replacedRange.status, 206);
  assert.deepEqual(Buffer.from(await replacedRange.arrayBuffer()), nextPackageBytes.subarray(0, 8));

  const wasloraDownload = await fetch(`${origin}/api/apps/waslora/download`, { redirect: 'manual' });
  assert.equal(wasloraDownload.status, 302);
  assert.equal(wasloraDownload.headers.get('location'), 'https://downloads.example/waslora.apk');

  const dashboard = await jsonRequest('/api/developer/apps', { cookie });
  assert.equal(dashboard.body.apps.length, 1);
  assert.equal(dashboard.body.apps[0].downloads, 2);
  assert.equal(dashboard.body.apps[0].version, '1.3.0');
  assert.equal(Object.values(dashboard.body.apps[0].dailyDownloads).reduce((a, b) => a + b, 0), 2);

  const deniedDelete = await fetch(`${origin}/api/developer/apps/${slug}`, { method: 'DELETE' });
  assert.equal(deniedDelete.status, 401);
  const deleted = await fetch(`${origin}/api/developer/apps/${slug}`, { method: 'DELETE', headers: { Cookie: cookie } });
  assert.equal(deleted.status, 200);
  assert.equal((await jsonRequest('/api/apps')).body.apps.length, 1);
  const deletedAccount = await fetch(`${origin}/api/me`, { method: 'DELETE', headers: { Cookie: cookie } });
  assert.equal(deletedAccount.status, 200);
  assert.equal((await jsonRequest('/api/me', { cookie })).response.status, 401);
});
