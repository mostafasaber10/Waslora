(() => {
  const $ = (id) => document.getElementById(id);
  const bridge = window.Waslora;
  let mp3Worker = null;
  let mp3Job = '';
  let activeFilter = 'all';
  const formatBytes = (n) => {
    if (!n) return '0 بايت';
    const units = ['بايت','KB','MB','GB']; let i = 0; let value = n;
    while (value >= 1024 && i < units.length - 1) { value /= 1024; i++; }
    return `${value.toFixed(i ? 1 : 0)} ${units[i]}`;
  };
  const showStatus = (id, text, error = false) => {
    const el = $(id); el.textContent = text; el.classList.remove('hidden','error'); if (error) el.classList.add('error');
  };
  const nativeCall = (method, args, statusId = 'downloadStatus') => {
    if (!bridge || typeof bridge[method] !== 'function') {
      showStatus(statusId, 'تعذر الاتصال بمحرك Android. أغلق Waslora وافتحه من جديد، وإذا استمرت المشكلة أعد تثبيت أحدث نسخة.', true);
      return false;
    }
    try { bridge[method](...args); return true; }
    catch (error) { showStatus(statusId, `تعذر بدء العملية: ${error && error.message || 'خطأ غير معروف'}`, true); return false; }
  };
  document.querySelectorAll('.tab').forEach((tab) => tab.addEventListener('click', () => {
    document.querySelectorAll('.tab,.screen').forEach((el) => el.classList.remove('active'));
    tab.classList.add('active'); $(`screen-${tab.dataset.screen}`).classList.add('active');
    if (tab.dataset.screen === 'library') loadFiles();
  }));
  $('downloadForm').addEventListener('submit', (event) => {
    event.preventDefault(); const url = $('videoUrl').value.trim();
    if (!url) return;
    showStatus('downloadStatus', 'جارٍ فحص الرابط وبدء التنزيل…');
    nativeCall('downloadFromUrl', [url]);
  });
  $('reloadFiles').addEventListener('click', loadFiles);
  document.querySelectorAll('.filter').forEach((button) => button.addEventListener('click', () => {
    document.querySelectorAll('.filter').forEach((item) => item.classList.remove('active'));
    button.classList.add('active'); activeFilter = button.dataset.filter || 'all'; loadFiles();
  }));
  $('refreshButton').addEventListener('click', () => { loadFiles(); nativeCall('refreshTransferStatus', []); });
  $('pickVideo').addEventListener('click', () => nativeCall('pickVideo', [$('audioFormat').value], 'convertStatus'));
  $('convertShared').addEventListener('click', () => nativeCall('convertShared', [$('audioFormat').value], 'convertStatus'));
  $('startTransfer').addEventListener('click', () => {
    $('startTransfer').disabled = true;
    if (!nativeCall('startTransfer', [], 'transferSubheading')) $('startTransfer').disabled = false;
  });
  $('pickPhoneFiles').addEventListener('click', () => nativeCall('pickPhoneFilesForTransfer', [], 'phoneFilesStatus'));
  $('stopTransfer').addEventListener('click', () => nativeCall('stopTransfer', [], 'transferSubheading'));
  $('copyUrl').addEventListener('click', async () => {
    const value = $('transferUrl').value;
    try { await navigator.clipboard.writeText(value); $('copyUrl').textContent = 'تم النسخ'; }
    catch (_) { $('transferUrl').select(); document.execCommand('copy'); $('copyUrl').textContent = 'تم النسخ'; }
    setTimeout(() => { $('copyUrl').textContent = 'نسخ'; }, 1600);
  });

  function fileCategory(name) {
    if (/\.(mp4|m4v|mov|mkv|webm|3gp)$/i.test(name)) return 'video';
    if (/\.(mp3|m4a|aac|wav|ogg|flac)$/i.test(name)) return 'audio';
    if (/\.(jpg|jpeg|png|gif|webp|heic|heif|bmp|svg)$/i.test(name)) return 'image';
    return 'other';
  }

  function loadFiles() {
    if (!bridge || typeof bridge.listDownloads !== 'function') {
      showStatus('downloadStatus', 'تعذر الاتصال بمحرك Android. أغلق Waslora وافتحه من جديد، وإذا استمرت المشكلة أعد تثبيت أحدث نسخة.', true);
      return;
    }
    try {
      const files = JSON.parse(bridge.listDownloads()).filter((file) => activeFilter === 'all' || fileCategory(file.name) === activeFilter);
      const list = $('downloadsList'); list.replaceChildren();
      if (!files.length) { list.innerHTML = '<div class="empty">لا توجد ملفات بعد.</div>'; return; }
      files.forEach((file) => {
        const row = document.createElement('div'); row.className = 'file-row';
        const icon = document.createElement('div'); icon.className = 'file-icon';
        icon.textContent = fileCategory(file.name) === 'audio' ? '♫' : fileCategory(file.name) === 'image' ? '▧' : fileCategory(file.name) === 'video' ? '▶' : '•';
        const info = document.createElement('div'); info.className = 'file-info';
        const name = document.createElement('strong'); name.textContent = file.name;
        const size = document.createElement('small'); size.textContent = formatBytes(file.size);
        info.append(name,size); row.append(icon,info);
        const open = document.createElement('button'); open.className = 'file-action'; open.textContent = 'فتح';
        open.addEventListener('click', () => nativeCall('openDownload', [file.name], 'downloadStatus'));
        const share = document.createElement('button'); share.className = 'file-action'; share.textContent = 'مشاركة';
        share.addEventListener('click', () => nativeCall('shareDownload', [file.name], 'downloadStatus'));
        row.append(open, share);
        if (/\.(mp4|m4v|mov|mkv|webm|3gp)$/i.test(file.name)) {
          const convert = document.createElement('button'); convert.className = 'convert-button'; convert.textContent = 'استخراج الصوت';
          convert.addEventListener('click', () => { document.querySelector('[data-screen="convert"]').click(); bridge.convertDownloaded(file.name, $('audioFormat').value); });
          row.append(convert);
        }
        list.append(row);
      });
    } catch (_) { $('downloadsList').innerHTML = '<div class="empty">تعذر قراءة الملفات.</div>'; }
  }

  function ensureMp3Worker() {
    if (mp3Worker) return mp3Worker;
    try {
      mp3Worker = new Worker('mp3-worker.js');
      mp3Worker.onmessage = (event) => {
        const message = event.data || {};
        if (message.type === 'ready') showStatus('convertStatus', 'بدأ ترميز MP3 على الهاتف…');
        else if (message.type === 'data') bridge.writeMp3Chunk(message.jobId, message.data, false);
        else if (message.type === 'pcm-ack') bridge.mp3PcmAck(message.jobId);
        else if (message.type === 'finish') {
          bridge.writeMp3Chunk(message.jobId, message.data || '', true);
          mp3Worker.terminate(); mp3Worker = null; mp3Job = '';
        } else if (message.type === 'error') {
          bridge.failMp3Conversion(message.jobId, message.message || 'تعذر ترميز MP3.');
          mp3Worker.terminate(); mp3Worker = null; mp3Job = '';
        }
      };
      mp3Worker.onerror = () => {
        if (mp3Job) bridge.failMp3Conversion(mp3Job, 'تعذر تشغيل محرك MP3 على هذا الجهاز.');
        mp3Worker.terminate(); mp3Worker = null; mp3Job = '';
      };
      return mp3Worker;
    } catch (error) {
      if (bridge && bridge.failMp3Conversion) bridge.failMp3Conversion(mp3Job, 'تعذر تشغيل محرك MP3 على هذا الجهاز.');
      return null;
    }
  }

  window.WasloraUI = {
    startMp3Encoder(jobId, sampleRate, channels, bitrate) {
      mp3Job = jobId;
      const worker = ensureMp3Worker();
      if (worker) worker.postMessage({type:'start', jobId, sampleRate, channels, bitrate});
    },
    pushMp3Pcm(jobId, data) {
      if (mp3Worker && jobId === mp3Job) mp3Worker.postMessage({type:'pcm', jobId, data});
    },
    finishMp3Encoder(jobId) {
      if (mp3Worker && jobId === mp3Job) mp3Worker.postMessage({type:'finish', jobId});
    },
    abortMp3Encoder(jobId) {
      if (mp3Worker && jobId === mp3Job) { mp3Worker.terminate(); mp3Worker = null; mp3Job = ''; }
    },
    onEvent(type, payload) {
      if (type === 'download-progress') {
        const percent = payload.total > 0 ? Math.floor(payload.done * 100 / payload.total) : 0;
        showStatus('downloadStatus', `جارٍ التنزيل… ${percent}% · ${formatBytes(payload.done)}${payload.total ? ' من ' + formatBytes(payload.total) : ''}`);
      } else if (type === 'download-complete') {
        showStatus('downloadStatus', `اكتمل التنزيل: ${payload.name} · ${formatBytes(payload.size)} · ${payload.location || 'Download/Waslora'}`); loadFiles();
      } else if (type === 'download-error') {
        showStatus('downloadStatus', payload.message, true);
      }
      else if (type === 'file-error') showStatus('downloadStatus', payload.message || 'تعذر فتح الملف.', true);
      else if (type === 'download-message') showStatus('downloadStatus', payload.message || 'جارٍ التنزيل…');
      else if (type === 'convert-progress') showStatus('convertStatus', payload.message);
      else if (type === 'convert-complete') { showStatus('convertStatus', `اكتمل التحويل: ${payload.name} · ${payload.location || 'Download/Waslora'}`); loadFiles(); }
      else if (type === 'convert-error') showStatus('convertStatus', payload.message, true);
      else if (type === 'shared-video') {
        document.querySelector('[data-screen="convert"]').click();
        $('convertShared').classList.remove('hidden');
        showStatus('convertStatus', payload.message || 'تم استلام الفيديو. اختر الصيغة ثم حوّله.');
      }
      else if (type === 'shared-video-cleared') $('convertShared').classList.add('hidden');
      else if (type === 'notice') showStatus('downloadStatus', payload.message);
      else if (type === 'phone-files-imported') { showStatus('phoneFilesStatus', payload.message || 'انتهى تجهيز الملفات.'); loadFiles(); }
      else if (type === 'transfer-status') {
        $('startTransfer').disabled = false;
        const on = payload.running && payload.url;
        $('transferHeading').textContent = on ? 'صفحة النقل جاهزة' : 'النقل متوقف';
        $('transferSubheading').textContent = on ? 'اكتب العنوان في متصفح اللابتوب المتصل بالشبكة نفسها.' : (payload.message || 'شغّل صفحة النقل عندما يكون اللابتوب على شبكة Wi‑Fi نفسها.');
        $('transferUrlBox').classList.toggle('hidden', !on); $('stopTransfer').classList.toggle('hidden', !on);
        $('transferUrl').value = on ? payload.url : ''; document.querySelector('.pulse').classList.toggle('on', !!on);
      }
    }
  };
  loadFiles();
  if (bridge) nativeCall('refreshTransferStatus', []);
  else showStatus('downloadStatus', 'تعذر الاتصال بمحرك Android. أغلق Waslora وافتحه من جديد، وإذا استمرت المشكلة أعد تثبيت أحدث نسخة.', true);
})();
