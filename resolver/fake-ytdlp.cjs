const fs = require('node:fs');
const path = require('node:path');

const outputIndex = process.argv.indexOf('--output');
if (outputIndex < 0 || !process.argv[outputIndex + 1]) process.exit(2);
const template = process.argv[outputIndex + 1];
const output = template.replace(/%\([^)]*\)[^/\\]*/g, 'smoke-video.mp4');
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, Buffer.from('fake-video'));
process.stderr.write('[download] 100.0% of 10.00KiB at 1.00MiB/s ETA 00:00\n');
