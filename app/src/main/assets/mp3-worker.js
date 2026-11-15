importScripts('lame.min.js');

let encoder = null;
let channels = 2;
let activeJob = '';

function encodeBase64(bytes) {
  let binary = '';
  const block = 8192;
  for (let i = 0; i < bytes.length; i += block) {
    const part = bytes.subarray(i, Math.min(i + block, bytes.length));
    binary += String.fromCharCode.apply(null, part);
  }
  return btoa(binary);
}

function pcmArrays(base64) {
  const binary = atob(base64);
  const sampleCount = Math.floor(binary.length / 2);
  const samples = new Int16Array(sampleCount);
  for (let i = 0; i < sampleCount; i++) {
    const lo = binary.charCodeAt(i * 2);
    const hi = binary.charCodeAt(i * 2 + 1);
    let value = (hi << 8) | lo;
    if (value & 0x8000) value -= 0x10000;
    samples[i] = value;
  }
  if (channels === 1) return [samples];
  const frames = Math.floor(samples.length / channels);
  const left = new Int16Array(frames);
  const right = new Int16Array(frames);
  for (let i = 0; i < frames; i++) {
    left[i] = samples[i * channels];
    right[i] = samples[i * channels + 1];
  }
  return [left, right];
}

self.onmessage = function (event) {
  const message = event.data || {};
  try {
    if (message.type === 'start') {
      activeJob = message.jobId;
      channels = message.channels === 1 ? 1 : 2;
      encoder = new lamejs.Mp3Encoder(channels, message.sampleRate, message.bitrate || 192);
      self.postMessage({ type: 'ready', jobId: activeJob });
      return;
    }
    if (!encoder || message.jobId !== activeJob) return;
    if (message.type === 'pcm') {
      const arrays = pcmArrays(message.data);
      const mp3 = arrays.length === 1 ? encoder.encodeBuffer(arrays[0]) : encoder.encodeBuffer(arrays[0], arrays[1]);
      if (mp3.length) self.postMessage({ type: 'data', jobId: activeJob, data: encodeBase64(mp3) });
      self.postMessage({ type: 'pcm-ack', jobId: activeJob });
      return;
    }
    if (message.type === 'finish') {
      const mp3 = encoder.flush();
      self.postMessage({ type: 'finish', jobId: activeJob, data: mp3.length ? encodeBase64(mp3) : '' });
      encoder = null;
      activeJob = '';
    }
  } catch (error) {
    self.postMessage({ type: 'error', jobId: activeJob, message: String(error && error.message || error) });
    encoder = null;
    activeJob = '';
  }
};
