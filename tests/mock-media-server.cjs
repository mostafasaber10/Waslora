const http = require('node:http');

const port = Number(process.env.WASLORA_TEST_PORT || 8765);
const size = 9 * 1024 * 1024;
const content = Buffer.alloc(size);
for (let i = 0; i < size; i++) content[i] = (i * 31 + (i >>> 8)) & 0xff;

const server = http.createServer((request, response) => {
  process.stdout.write(`${request.method} ${request.url} Range=${request.headers.range || '-'}\n`);
  response.setHeader('Content-Type', 'video/mp4');
  response.setHeader('Accept-Ranges', 'bytes');
  response.setHeader('Content-Disposition', 'attachment; filename="waslora-test-video.mp4"');
  if (request.method === 'HEAD') {
    response.setHeader('Content-Length', content.length);
    response.writeHead(200);
    response.end();
    return;
  }
  const range = request.headers.range;
  if (range) {
    const match = /^bytes=(\d+)-(\d+)$/.exec(range);
    if (!match) { response.writeHead(416); response.end(); return; }
    const start = Number(match[1]); const end = Math.min(Number(match[2]), content.length - 1);
    if (start < 0 || start > end || end >= content.length) { response.writeHead(416); response.end(); return; }
    response.writeHead(206, { 'Content-Length': end - start + 1, 'Content-Range': `bytes ${start}-${end}/${content.length}` });
    response.end(content.subarray(start, end + 1));
    return;
  }
  response.writeHead(200, { 'Content-Length': content.length });
  response.end(content);
});

server.listen(port, '0.0.0.0', () => process.stdout.write(`Mock media server listening on 0.0.0.0:${port} (${size} bytes)\n`));
process.on('SIGINT', () => server.close(() => process.exit(0)));
