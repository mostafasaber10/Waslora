(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const categories = ['All apps', 'Tools', 'Video & Audio', 'Productivity', 'Photo & Design', 'Education', 'Games', 'Lifestyle', 'Other'];
  const state = { apps: [], total: 0, page: 1, hasMore: false, category: 'All apps', search: '', sort: 'featured', user: null, developerApps: [], authMode: 'login', editingApp: null, pendingUploadApp: null };
  const number = new Intl.NumberFormat('en-US');

  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
  }

  async function api(path, options = {}) {
    const response = await fetch(path, { credentials: 'same-origin', ...options, headers: { ...(options.body && !(options.body instanceof File) ? { 'Content-Type': 'application/json' } : {}), ...(options.headers || {}) } });
    const result = response.status === 204 ? {} : await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error || 'Something went wrong. Please try again.');
    return result;
  }

  function appIcon(app, className = 'app-icon') {
    const source = String(app.iconUrl || '/assets/tata-mark.svg');
    const safeSource = /^(https:\/\/|\/assets\/)/i.test(source) ? escapeHtml(source) : '/assets/tata-mark.svg';
    return `<img class="${className}" src="${safeSource}" alt="" loading="lazy">`;
  }

  function formatSize(bytes) {
    if (!bytes) return 'Android app';
    const mb = bytes / (1024 * 1024);
    return `${mb >= 100 ? Math.round(mb) : mb.toFixed(1)} MB`;
  }

  function recentDownloads(app) {
    const daily = app.dailyDownloads || {};
    const dates = Array.from({ length: 7 }, (_, index) => {
      const date = new Date();
      date.setUTCHours(0, 0, 0, 0);
      date.setUTCDate(date.getUTCDate() - (6 - index));
      return { key: date.toISOString().slice(0, 10), label: new Intl.DateTimeFormat('en-US', { weekday: 'short', timeZone: 'UTC' }).format(date) };
    });
    const highest = Math.max(1, ...dates.map(({ key }) => Number(daily[key] || 0)));
    return `<div class="developer-history" aria-label="Download requests over the last seven days"><small>LAST 7 DAYS</small><div class="developer-history-bars">${dates.map(({ key, label }) => {
      const count = Number(daily[key] || 0);
      const level = count ? Math.max(1, Math.ceil(count / highest * 5)) : 0;
      return `<span class="history-day" title="${label}: ${number.format(count)} requests"><i class="day-bar level-${level}"></i><small>${label}</small></span>`;
    }).join('')}</div></div>`;
  }

  function renderCategories() {
    $('categoryList').innerHTML = categories.map((item) => `<button type="button" class="category-chip${item === state.category ? ' active' : ''}" data-category="${escapeHtml(item)}">${escapeHtml(item)}</button>`).join('');
    $('categoryList').querySelectorAll('[data-category]').forEach((button) => button.addEventListener('click', () => {
      state.category = button.dataset.category;
      renderCategories();
      loadApps();
    }));
  }

  async function loadApps(append = false) {
    if (!append) state.page = 1;
    const params = new URLSearchParams({ page: String(state.page), limit: '24', sort: state.sort });
    if (state.search.trim()) params.set('q', state.search.trim());
    if (state.category !== 'All apps') params.set('category', state.category);
    try {
      const response = await api(`/api/apps?${params}`);
      state.apps = append ? [...state.apps, ...response.apps] : response.apps;
      state.total = response.total;
      state.hasMore = response.hasMore;
      renderApps();
    } catch {
      $('appGrid').innerHTML = '<div class="loading-card">The store could not connect to its catalog. Refresh and try again.</div>';
    }
  }

  function renderApps() {
    const apps = state.apps;
    $('resultsLabel').textContent = state.search || state.category !== 'All apps' ? `${number.format(state.total)} ${state.total === 1 ? 'app' : 'apps'} found` : 'Featured apps';
    $('catalogCount').innerHTML = `${number.format(state.total)} ${state.total === 1 ? 'app' : 'apps'} to discover <span>✳</span>`;
    $('emptyState').classList.toggle('hidden', state.total !== 0);
    $('appGrid').classList.toggle('hidden', apps.length === 0);
    $('loadMoreWrap').classList.toggle('hidden', !state.hasMore);
    $('appGrid').innerHTML = apps.map((app) => `<article class="app-card" data-open-app="${escapeHtml(app.slug)}" tabindex="0" role="button" aria-label="View ${escapeHtml(app.name)} details">
      <div class="card-top">${appIcon(app)}<div class="app-title-wrap"><h3 class="app-title">${escapeHtml(app.name)}<span class="verified-mark" title="Published on Tata Store">✦</span></h3><p class="app-developer">${escapeHtml(app.developerName)}</p></div><span class="app-category">${escapeHtml(app.category)}</span></div>
      <p class="app-description">${escapeHtml(app.tagline || app.description)}</p>
      <div class="card-footer"><span class="app-version">v${escapeHtml(app.version)} · ${escapeHtml(app.platform || 'Android')}</span><span class="download-count">↓ ${number.format(app.downloads || 0)}</span><button class="mini-download" type="button" data-download="${escapeHtml(app.slug)}">Get app&nbsp; ↗</button></div>
    </article>`).join('');
    $('appGrid').querySelectorAll('[data-open-app]').forEach((card) => {
      card.addEventListener('click', (event) => { if (!event.target.closest('[data-download]')) openDetails(card.dataset.openApp); });
      card.addEventListener('keydown', (event) => { if ((event.key === 'Enter' || event.key === ' ') && event.target === card) { event.preventDefault(); openDetails(card.dataset.openApp); } });
    });
    $('appGrid').querySelectorAll('[data-download]').forEach((button) => button.addEventListener('click', (event) => { event.stopPropagation(); window.location.assign(`/api/apps/${encodeURIComponent(button.dataset.download)}/download`); }));
    wireImageFallbacks($('appGrid'));
  }

  function wireImageFallbacks(root) {
    root.querySelectorAll('img.app-icon, img.detail-icon').forEach((image) => image.addEventListener('error', () => { image.src = '/assets/tata-mark.svg'; }, { once: true }));
  }

  async function openDetails(slug, updateUrl = true) {
    let app = state.apps.find((item) => item.slug === slug);
    if (!app) {
      try { app = (await api(`/api/apps/${encodeURIComponent(slug)}`)).app; }
      catch (error) { toast(error.message); return; }
    }
    if (updateUrl && window.location.pathname !== `/apps/${slug}`) window.history.pushState({ appSlug: slug }, '', `/apps/${encodeURIComponent(slug)}`);
    $('detailContent').innerHTML = `<div class="detail-content"><div class="detail-hero">${appIcon(app, 'detail-icon')}<div><h2 id="detailTitle">${escapeHtml(app.name)}</h2><p>by ${escapeHtml(app.developerName)}</p><span class="detail-category">${escapeHtml(app.category)}</span></div><button class="share-app-button" type="button" id="shareAppButton">Share ↗</button></div><p class="detail-description">${escapeHtml(app.description)}</p><div class="detail-stats"><div><small>Version</small><b>${escapeHtml(app.version)}</b></div><div><small>Platform</small><b>${escapeHtml(app.platform || 'Android')}</b></div><div><small>Package size</small><b>${formatSize(app.sizeBytes)}</b></div><div><small>Downloads</small><b>${number.format(app.downloads || 0)}</b></div></div><button class="button button-lime detail-download" type="button" data-detail-download="${escapeHtml(app.slug)}">Download for Android <span>↓</span></button><p class="analytics-note">Only download apps from creators you trust. Android may ask you to allow installation from your browser.</p></div>`;
    $('detailContent').querySelector('[data-detail-download]').addEventListener('click', () => window.location.assign(`/api/apps/${encodeURIComponent(app.slug)}/download`));
    $('detailContent').querySelector('.detail-description').insertAdjacentHTML('afterend', '<button class="report-app-button" type="button" id="reportAppButton">Report this app</button><form class="report-form hidden" id="reportForm"><textarea name="message" minlength="20" maxlength="1500" rows="3" placeholder="Tell us why this app should be reviewed (20–1,500 characters)." required></textarea><input name="email" type="email" maxlength="254" placeholder="Email for follow-up (optional)"><button class="button button-quiet" type="submit">Send report</button><p class="form-message" id="reportMessage" role="status"></p></form>');
    $('reportAppButton').addEventListener('click', () => $('reportForm').classList.toggle('hidden'));
    $('reportForm').addEventListener('submit', async (event) => {
      event.preventDefault();
      const data = new FormData(event.currentTarget);
      try {
        await api(`/api/apps/${encodeURIComponent(app.slug)}/report`, { method: 'POST', body: JSON.stringify({ message: data.get('message'), email: data.get('email') }) });
        setFormMessage($('reportMessage'), 'Thank you. The store team will review this report.');
        event.currentTarget.reset();
      } catch (error) { setFormMessage($('reportMessage'), error.message, true); }
    });
    $('shareAppButton').addEventListener('click', async () => {
      const shareData = { title: `${app.name} · Tata Store`, text: app.tagline, url: `${window.location.origin}/apps/${encodeURIComponent(app.slug)}` };
      try {
        if (navigator.share) await navigator.share(shareData);
        else { await navigator.clipboard.writeText(shareData.url); toast('App link copied.'); }
      } catch (error) { if (error.name !== 'AbortError') toast('Could not share this link.'); }
    });
    wireImageFallbacks($('detailContent'));
    if (!$('detailDialog').open) $('detailDialog').showModal();
  }

  function toast(message) {
    $('toast').textContent = message;
    $('toast').classList.remove('hidden');
    window.setTimeout(() => $('toast').classList.add('hidden'), 3500);
  }

  function authMessage(message, error = false) {
    $('authMessage').textContent = message;
    $('authMessage').classList.toggle('error', error);
  }

  function setAuthMode(mode) {
    state.authMode = mode;
    const register = mode === 'register';
    $('loginTab').classList.toggle('active', !register);
    $('registerTab').classList.toggle('active', register);
    $('authTitle').textContent = register ? 'Start your studio.' : 'Welcome back.';
    $('authSubtitle').textContent = register ? 'Create your developer account and share your first app.' : 'Sign in to manage your apps and see your downloads.';
    $('authSubmit').innerHTML = register ? 'Create developer account <span>→</span>' : 'Sign in <span>→</span>';
    $('displayNameField').classList.toggle('hidden', !register);
    $('setupTokenField').classList.toggle('hidden', !register);
    $('authForm').elements.displayName.required = register;
    $('authForm').elements.password.autocomplete = register ? 'new-password' : 'current-password';
    $('authForm').elements.password.minLength = register ? 12 : 1;
    $('authForm').elements.password.placeholder = register ? 'At least 12 characters' : 'Your password';
    authMessage('');
  }

  async function openAccount() {
    if (!state.user) {
      $('authDialog').showModal();
      return;
    }
    try {
      const response = await api('/api/developer/apps');
      state.developerApps = response.apps;
      renderDashboard();
      if (state.user.role === 'admin') await loadAdminReview();
      $('developerDialog').showModal();
    } catch (error) { toast(error.message); }
  }

  async function loadAdminReview() {
    if (state.user?.role !== 'admin') return;
    const [appsResult, reportsResult] = await Promise.all([api('/api/admin/apps'), api('/api/admin/reports')]);
    $('reviewAppList').innerHTML = appsResult.apps.length ? appsResult.apps.map((app) => `<article class="review-card"><h4>${escapeHtml(app.name)} · v${escapeHtml(app.version)}</h4><small>${escapeHtml(app.developerName)} (${escapeHtml(app.ownerEmail || '')}) · ${escapeHtml(app.category)} · ${formatSize(app.sizeBytes)}</small><p>${escapeHtml(app.description)}</p><div class="review-actions"><button type="button" class="button reject-button" data-review-app="${escapeHtml(app.slug)}" data-review-status="rejected">Reject</button><button type="button" class="button button-lime" data-review-app="${escapeHtml(app.slug)}" data-review-status="approved">Approve & publish</button></div></article>`).join('') : '<div class="review-empty">No apps are waiting for review.</div>';
    $('reportList').innerHTML = reportsResult.reports.length ? reportsResult.reports.map((report) => `<article class="review-card"><h4>${escapeHtml(report.appName)}</h4><small>${escapeHtml(report.email || 'No contact email')} · ${new Date(report.createdAt).toLocaleString()}</small><p>${escapeHtml(report.message)}</p><div class="review-actions"><button type="button" class="button reject-button" data-report-status="dismissed" data-report-id="${escapeHtml(report.id)}">Dismiss</button><button type="button" class="button button-quiet" data-report-status="resolved" data-report-id="${escapeHtml(report.id)}">Resolve</button></div></article>`).join('') : '<div class="review-empty">No open reports.</div>';
    $('reviewAppList').querySelectorAll('[data-review-app]').forEach((button) => button.addEventListener('click', async () => {
      const note = button.dataset.reviewStatus === 'rejected' ? window.prompt('Optional: explain what the developer should change.') || '' : '';
      try {
        await api(`/api/admin/apps/${encodeURIComponent(button.dataset.reviewApp)}/review`, { method: 'PATCH', body: JSON.stringify({ status: button.dataset.reviewStatus, note }) });
        await Promise.all([loadAdminReview(), loadApps()]);
        toast(button.dataset.reviewStatus === 'approved' ? 'App approved and published.' : 'App returned to the developer.');
      } catch (error) { toast(error.message); }
    }));
    $('reportList').querySelectorAll('[data-report-id]').forEach((button) => button.addEventListener('click', async () => {
      try {
        await api(`/api/admin/reports/${encodeURIComponent(button.dataset.reportId)}`, { method: 'PATCH', body: JSON.stringify({ status: button.dataset.reportStatus }) });
        await loadAdminReview();
      } catch (error) { toast(error.message); }
    }));
  }

  function renderDashboard() {
    const apps = state.developerApps;
    const total = apps.reduce((sum, app) => sum + (app.downloads || 0), 0);
    const monthKey = new Date().toISOString().slice(0, 7);
    const thisMonth = apps.reduce((sum, app) => sum + Object.entries(app.dailyDownloads || {}).filter(([day]) => day.startsWith(monthKey)).reduce((subtotal, [, count]) => subtotal + count, 0), 0);
    $('dashAppCount').textContent = number.format(apps.length);
    $('dashDownloads').textContent = number.format(total);
    $('dashMonthDownloads').textContent = number.format(thisMonth);
    $('developerAppList').innerHTML = apps.length ? apps.map((app) => {
      const label = app.status === 'pending' ? 'Pending review' : app.status === 'rejected' ? `Needs changes${app.reviewNote ? ` · ${escapeHtml(app.reviewNote)}` : ''}` : app.published ? 'Published' : 'Draft';
      return `<div class="developer-card">${appIcon(app)}<div class="developer-card-info"><b>${escapeHtml(app.name)}</b><span>${label} · v${escapeHtml(app.version)}</span></div>${recentDownloads(app)}<div class="developer-downloads"><b>${number.format(app.downloads || 0)}</b><small>DOWNLOADS</small></div><div class="developer-card-actions"><button type="button" class="text-button" data-manage-app="${escapeHtml(app.slug)}">${app.published ? 'Manage' : 'Finish setup'}</button><button type="button" class="delete-app" data-delete-app="${escapeHtml(app.slug)}" aria-label="Delete ${escapeHtml(app.name)}">×</button></div></div>`;
    }).join('') : '<div class="dashboard-empty"><b>Your first app starts here.</b>Add an app and share it with the world.</div>';
    $('adminReview').classList.toggle('hidden', state.user?.role !== 'admin');
    $('claimAdminButton').classList.toggle('hidden', state.user?.role === 'admin');
    $('developerAppList').querySelectorAll('[data-manage-app]').forEach((button) => button.addEventListener('click', () => {
      const app = state.developerApps.find((item) => item.slug === button.dataset.manageApp);
      if (!app) return;
      state.editingApp = app;
      state.pendingUploadApp = app.published ? null : app;
      const form = $('uploadForm');
      form.elements.name.value = app.name;
      form.elements.tagline.value = app.tagline;
      form.elements.description.value = app.description;
      form.elements.category.value = app.category;
      form.elements.version.value = app.version;
      form.elements.iconUrl.value = /^https:\/\//i.test(app.iconUrl) ? app.iconUrl : '';
      form.elements.package.required = !app.sizeBytes;
      $('uploadFormTitle').textContent = app.published ? 'Manage or update your app' : 'Finish publishing your app';
      $('publishButton').innerHTML = app.published ? 'Save app changes <span>↗</span>' : 'Publish app <span>↗</span>';
      $('fileName').textContent = app.sizeBytes ? 'Leave package empty to keep the current version.' : 'Choose the signed APK to finish publishing.';
      setFormMessage($('uploadMessage'), '');
      showUpload(true);
    }));
    $('developerAppList').querySelectorAll('[data-delete-app]').forEach((button) => button.addEventListener('click', async () => {
      if (!window.confirm('Remove this app and its package from Tata Store?')) return;
      try {
        await api(`/api/developer/apps/${encodeURIComponent(button.dataset.deleteApp)}`, { method: 'DELETE' });
        state.developerApps = state.developerApps.filter((app) => app.slug !== button.dataset.deleteApp);
        renderDashboard(); await loadApps(); toast('App removed from the store.');
      } catch (error) { toast(error.message); }
    }));
  }

  function showUpload(show) {
    $('uploadPanel').classList.toggle('hidden', !show);
    if (show) $('uploadPanel').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  function setFormMessage(node, message, error = false) {
    node.textContent = message;
    node.classList.toggle('error', error);
  }

  function uploadPackage(app, file, version) {
    return new Promise((resolve, reject) => {
      const request = new XMLHttpRequest();
      request.open('POST', `/api/developer/apps/${encodeURIComponent(app.slug)}/package`);
      request.setRequestHeader('Content-Type', 'application/vnd.android.package-archive');
      request.setRequestHeader('X-Store-Version', version || app.version);
      request.upload.addEventListener('progress', (event) => {
        if (event.lengthComputable) $('publishButton').innerHTML = `Uploading ${Math.round(event.loaded / event.total * 100)}%…`;
      });
      request.addEventListener('load', () => {
        let result = {};
        try { result = JSON.parse(request.responseText); } catch {}
        if (request.status >= 200 && request.status < 300) resolve(result);
        else reject(new Error(result.error || 'The package upload failed.'));
      });
      request.addEventListener('error', () => reject(new Error('Upload interrupted. Check your connection and try again.')));
      request.send(file);
    });
  }

  async function initialize() {
    $('year').textContent = new Date().getFullYear();
    renderCategories();
    await loadApps();
    try {
      const session = await api('/api/me');
      state.user = session.user;
      $('accountButton').textContent = state.user.role === 'admin' ? 'Store admin' : 'My developer dashboard';
      $('headerSubmit').textContent = 'Add an app ↗';
    } catch {}

    let searchTimer;
    $('searchInput').addEventListener('input', (event) => { state.search = event.target.value; window.clearTimeout(searchTimer); searchTimer = window.setTimeout(() => loadApps(), 220); });
    $('sortSelect').addEventListener('change', (event) => { state.sort = event.target.value; loadApps(); });
    $('loadMoreButton').addEventListener('click', async (event) => { event.currentTarget.disabled = true; state.page += 1; await loadApps(true); event.currentTarget.disabled = false; });
    $('clearFilters').addEventListener('click', () => { state.category = 'All apps'; state.search = ''; $('searchInput').value = ''; renderCategories(); loadApps(); });
    $('accountButton').addEventListener('click', openAccount);
    $('headerSubmit').addEventListener('click', openAccount);
    $('developerCta').addEventListener('click', openAccount);
    $('refreshAdmin').addEventListener('click', () => loadAdminReview().catch((error) => toast(error.message)));
    $('claimAdminButton').addEventListener('click', async () => {
      const setupToken = window.prompt('Enter the private store setup key from the server operator.');
      if (setupToken === null) return;
      try {
        const result = await api('/api/admin/claim', { method: 'POST', body: JSON.stringify({ setupToken }) });
        state.user = result.user;
        $('accountButton').textContent = 'Store admin';
        renderDashboard();
        await loadAdminReview();
        toast('Store administrator access is ready.');
      } catch (error) { toast(error.message); }
    });
    $('showUploadButton').addEventListener('click', () => {
      state.editingApp = null;
      state.pendingUploadApp = null;
      const form = $('uploadForm'); form.reset(); form.elements.package.required = true;
      $('fileName').textContent = 'No file selected'; $('uploadFormTitle').textContent = 'Publish an Android app';
      $('publishButton').innerHTML = 'Publish app <span>↗</span>'; setFormMessage($('uploadMessage'), ''); showUpload(true);
    });
    $('cancelUpload').addEventListener('click', () => { state.editingApp = null; showUpload(false); });
    $('signOutButton').addEventListener('click', async () => {
      try { await api('/api/auth/logout', { method: 'POST', body: '{}' }); } catch {}
      state.user = null;
      $('developerDialog').close();
      $('accountButton').textContent = 'Developer sign in';
      $('headerSubmit').textContent = 'Submit an app ↗';
      toast('You have signed out.');
    });
    $('deleteAccountButton').addEventListener('click', async () => {
      if (!window.confirm('Delete your developer account, every app you published, and all uploaded APK files? This cannot be undone.')) return;
      try {
        await api('/api/me', { method: 'DELETE' });
        state.user = null; state.developerApps = []; state.pendingUploadApp = null; state.editingApp = null;
        $('developerDialog').close(); $('accountButton').textContent = 'Developer sign in'; $('headerSubmit').textContent = 'Submit an app ↗';
        await loadApps(); toast('Your developer account and apps have been deleted.');
      } catch (error) { toast(error.message); }
    });
    $('menuButton').addEventListener('click', () => {
      const open = $('mainNav').classList.toggle('open');
      $('menuButton').setAttribute('aria-expanded', String(open));
    });
    document.querySelectorAll('.nav-link').forEach((link) => link.addEventListener('click', () => {
      document.querySelectorAll('.nav-link').forEach((item) => item.classList.toggle('active', item === link));
      $('mainNav').classList.remove('open'); $('menuButton').setAttribute('aria-expanded', 'false');
    }));
    $('detailDialog').addEventListener('close', () => {
      if (window.location.pathname.startsWith('/apps/')) window.history.replaceState({}, '', '/');
    });
    $('loginTab').addEventListener('click', () => setAuthMode('login'));
    $('registerTab').addEventListener('click', () => setAuthMode('register'));
    $('authForm').addEventListener('submit', async (event) => {
      event.preventDefault();
      const data = new FormData(event.currentTarget);
      const register = state.authMode === 'register';
      $('authSubmit').disabled = true;
      authMessage(register ? 'Creating your account…' : 'Signing in…');
      try {
        const result = await api(`/api/auth/${register ? 'signup' : 'login'}`, { method: 'POST', body: JSON.stringify({ email: data.get('email'), password: data.get('password'), displayName: data.get('displayName'), adminSetupToken: data.get('adminSetupToken') }) });
        state.user = result.user;
        $('accountButton').textContent = state.user.role === 'admin' ? 'Store admin' : 'My developer dashboard';
        $('headerSubmit').textContent = 'Add an app ↗';
        $('authDialog').close();
        event.currentTarget.reset();
        toast(register ? 'Your developer account is ready.' : 'Welcome back.');
        await openAccount();
      } catch (error) { authMessage(error.message, true); }
      finally { $('authSubmit').disabled = false; }
    });
    $('uploadForm').elements.package.addEventListener('change', (event) => {
      const file = event.target.files[0]; $('fileName').textContent = file ? `${file.name} · ${formatSize(file.size)}` : 'No file selected';
    });
    $('uploadForm').addEventListener('submit', async (event) => {
      event.preventDefault();
      const form = event.currentTarget;
      const data = new FormData(form);
      const file = data.get('package');
      const hasFile = file instanceof File && file.size > 0;
      const editing = state.editingApp;
      if (!editing && !hasFile && !state.pendingUploadApp) { setFormMessage($('uploadMessage'), 'Choose an APK package to publish.', true); return; }
      if (editing && !hasFile && data.get('version') !== editing.version) { setFormMessage($('uploadMessage'), 'Choose the new APK package when changing the app version.', true); return; }
      if (hasFile && file.size > 300 * 1024 * 1024) { setFormMessage($('uploadMessage'), 'This APK is larger than the 300 MB limit.', true); return; }
      $('publishButton').disabled = true;
      setFormMessage($('uploadMessage'), 'Preparing your app…');
      try {
        const current = editing || state.pendingUploadApp;
        const values = { name: data.get('name'), tagline: data.get('tagline'), description: data.get('description'), category: data.get('category'), version: current ? current.version : data.get('version'), iconUrl: data.get('iconUrl') };
        let app;
        if (editing) {
          app = (await api(`/api/developer/apps/${encodeURIComponent(editing.slug)}`, { method: 'PATCH', body: JSON.stringify(values) })).app;
          if (hasFile) await uploadPackage(app, file, data.get('version'));
        } else if (state.pendingUploadApp) {
          app = (await api(`/api/developer/apps/${encodeURIComponent(state.pendingUploadApp.slug)}`, { method: 'PATCH', body: JSON.stringify(values) })).app;
          if (hasFile) await uploadPackage(app, file, data.get('version'));
        } else {
          app = state.pendingUploadApp;
          if (!app) { app = (await api('/api/developer/apps', { method: 'POST', body: JSON.stringify(values) })).app; state.pendingUploadApp = app; }
          if (hasFile) await uploadPackage(app, file, data.get('version'));
        }
        state.developerApps = await api('/api/developer/apps').then((response) => response.apps);
        state.pendingUploadApp = null; state.editingApp = null;
        await loadApps(); renderDashboard(); form.reset(); $('fileName').textContent = 'No file selected';
        form.elements.package.required = true; $('uploadFormTitle').textContent = 'Publish an Android app'; $('publishButton').innerHTML = 'Publish app <span>↗</span>';
        const message = hasFile ? 'Your APK was uploaded and is waiting for store review.' : 'Your app details have been updated.';
        setFormMessage($('uploadMessage'), message); toast(message);
      } catch (error) { setFormMessage($('uploadMessage'), error.message, true); }
      finally { $('publishButton').disabled = false; $('publishButton').innerHTML = 'Publish app <span>↗</span>'; }
    });
    window.addEventListener('keydown', (event) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); $('searchInput').focus(); }
      if (event.key === 'Escape') { $('mainNav').classList.remove('open'); $('menuButton').setAttribute('aria-expanded', 'false'); }
    });
    window.addEventListener('popstate', () => {
      const route = window.location.pathname.match(/^\/apps\/([a-z0-9-]+)$/);
      if (route) openDetails(route[1], false);
      else if ($('detailDialog').open) $('detailDialog').close();
    });
    const deepLink = window.location.pathname.match(/^\/apps\/([a-z0-9-]+)$/);
    if (deepLink) openDetails(deepLink[1], false);
  }

  initialize();
})();
