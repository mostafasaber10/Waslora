const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const assets = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');
const appHtml = fs.readFileSync(path.join(assets, 'index.html'), 'utf8');
const appScript = fs.readFileSync(path.join(assets, 'app.js'), 'utf8');
const activitySource = fs.readFileSync(path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'waslora', 'app', 'MainActivity.java'), 'utf8');
const localDownloaderSource = fs.readFileSync(path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'waslora', 'app', 'LocalPlatformDownloader.java'), 'utf8');
const transferServiceSource = fs.readFileSync(path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'waslora', 'app', 'LocalTransferService.java'), 'utf8');
const htmlIds = new Set([...appHtml.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]));
for (const [, id] of appScript.matchAll(/\$\('([^']+)'\)/g)) assert(htmlIds.has(id), `app.js references missing element #${id}`);
for (const [, method] of appScript.matchAll(/\bbridge\.([A-Za-z_$][\w$]*)\s*\(/g)) {
  assert(new RegExp(`public\\s+(?:void|String)\\s+${method}\\s*\\(`).test(activitySource), `app.js bridge method ${method} is missing from MainActivity`);
}
for (const [, method] of activitySource.matchAll(/window\.WasloraUI&&window\.WasloraUI\.([A-Za-z_$][\w$]*)\s*\(/g)) {
  assert(new RegExp(`\\b${method}\\s*\\(`).test(appScript), `MainActivity calls missing JavaScript method ${method}`);
}
assert(activitySource.includes('LocalPlatformDownloader.download'), 'platform links must use the on-device downloader');
assert(localDownloaderSource.includes('YoutubeDL.getInstance().execute'), 'platform downloads must execute yt-dlp locally');
assert(localDownloaderSource.includes('PlatformDownloadQuality.formatSelector(quality)'), 'platform downloads must apply the selected quality');
assert(localDownloaderSource.includes('PlatformDownloadQuality.formatSelector(quality)'), 'quality selection must be allowlisted before reaching yt-dlp');
assert(appHtml.includes('id="videoQuality"'), 'download screen must expose video quality choices');
assert(appScript.includes("nativeCall('downloadFromUrl', [url, quality])"), 'selected video quality must reach the Android bridge');
assert(appHtml.includes('TATA <span>STUDIOS</span>'), 'launch splash must credit Tata Studios');
assert(appHtml.includes('studioSplash'), 'launch splash must be present');
assert(!localDownloaderSource.includes('best[ext=mp4]'), 'platform downloads must not require an MP4 format unavailable on some links');
assert(!appHtml.includes('resolverEndpoint'), 'platform links must not require Resolver settings');
assert(transferServiceSource.includes('PublicFileStore.mimeType(name)'), 'phone-to-laptop transfers must retain media MIME types');
const laptopHtml = fs.readFileSync(path.join(assets, 'laptop.html'), 'utf8');
const laptopScript = laptopHtml.match(/<script>([\s\S]*?)<\/script>/i);
assert(laptopScript, 'laptop.html must include its local transfer script');
new vm.Script(laptopScript[1], { filename: 'laptop.html' });

const messages = [];
const context = {
  atob: (value) => Buffer.from(value, 'base64').toString('binary'),
  btoa: (value) => Buffer.from(value, 'binary').toString('base64'),
  console,
  self: { postMessage: (message) => messages.push(message) },
};
context.importScripts = (...files) => {
  for (const file of files) {
    const source = fs.readFileSync(path.join(assets, file), 'utf8');
    vm.runInContext(source, context, { filename: file });
  }
};
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(assets, 'mp3-worker.js'), 'utf8'), context, { filename: 'mp3-worker.js' });

context.self.onmessage({ data: { type: 'start', jobId: 'smoke', sampleRate: 44100, channels: 1, bitrate: 128 } });
const samples = new Int16Array(44100);
for (let i = 0; i < samples.length; i++) samples[i] = Math.round(Math.sin(2 * Math.PI * 440 * i / 44100) * 12000);
context.self.onmessage({ data: { type: 'pcm', jobId: 'smoke', data: Buffer.from(samples.buffer).toString('base64') } });
context.self.onmessage({ data: { type: 'finish', jobId: 'smoke' } });

assert.equal(messages[0].type, 'ready');
assert(messages.some((message) => message.type === 'pcm-ack'));
assert.equal(messages.at(-1).type, 'finish');
const encoded = Buffer.concat(messages.filter((message) => message.type === 'data' || message.type === 'finish')
  .map((message) => Buffer.from(message.data || '', 'base64')));
assert(encoded.length > 1000, `expected encoded MP3 data, got ${encoded.length} bytes`);
assert(encoded.some((byte, index) => byte === 0xff && index + 1 < encoded.length && (encoded[index + 1] & 0xe0) === 0xe0), 'expected an MPEG frame sync word');
console.log(`UI wiring, laptop script, and MP3 worker smoke tests passed (${encoded.length} encoded bytes).`);
