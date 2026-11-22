/* Batch Inspector UI behaviour. Vanilla JS, no build step. Every request it makes is a GET. */
(function () {
  'use strict';

  var $ = function (sel, root) { return (root || document).querySelector(sel); };
  var $$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };
  var body = document.body;
  var skew = (parseInt(body.dataset.serverNow, 10) || Date.now()) - Date.now();
  var detailPane = $('#detailPane');
  var detailSeq = 0;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; });
  }
  function fmtDur(ms) { return window.BatchTimeline ? window.BatchTimeline.fmtDur(ms) : String(ms); }
  function store(k, v) { try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { return null; } }
  function progress(on) { var p = $('#progress'); if (p) p.hidden = !on; }
  function isTyping(el) { return el && /^(INPUT|SELECT|TEXTAREA)$/.test(el.tagName); }

  // ------------------------------------------------------------------ theme
  function currentTheme() {
    return document.documentElement.getAttribute('data-theme') ||
      (window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  }
  function toggleTheme() {
    var next = currentTheme() === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    store('bi.theme', next);
  }
  $('#btnTheme').addEventListener('click', toggleTheme);
  $('#btnHelp').addEventListener('click', function () { $('#helpDlg').showModal(); });

  // ------------------------------------------------------------------ sidebar form
  var form = $('#filterForm');
  form.addEventListener('submit', function () { progress(true); });
  $$('.scard').forEach(function (a) { a.addEventListener('click', function () { progress(true); }); });
  $$('input[name="range"]').forEach(function (r) {
    r.addEventListener('change', function () { $('#customRange').hidden = (r.value !== 'custom' || !r.checked); });
  });
  $$('[data-max]').forEach(function (b) { b.addEventListener('click', function () { $('#max').value = b.getAttribute('data-max'); }); });
  $('#btnReset').addEventListener('click', function () { progress(true); });
  $('#btnRefresh').addEventListener('click', function () { progress(true); location.reload(); });

  var dbSelect = $('#db');
  if (dbSelect) {
    dbSelect.addEventListener('change', function () {
      var opt = dbSelect.options[dbSelect.selectedIndex];
      $('#testResult').textContent = '';
      var pill = $('.db-pill b');
      if (pill && opt) pill.textContent = opt.text.split('  ·  ')[0];
      // Only hit the database automatically if the user was already looking at results.
      if (body.dataset.searched === 'true') { $('#selectedInput').value = ''; progress(true); form.submit(); }
    });
  }

  var btnTest = $('#btnTest');
  if (btnTest) {
    btnTest.addEventListener('click', function () {
      var out = $('#testResult');
      out.className = 'hint';
      out.textContent = 'testing…';
      fetch('/databases/' + encodeURIComponent(dbSelect.value) + '/health', { headers: { Accept: 'application/json' } })
        .then(function (r) { return r.json(); })
        .then(function (h) {
          out.className = 'hint ' + (h.ok ? 'ok' : 'err');
          out.textContent = (h.ok ? '✓ ' : '✕ ') + h.message + (h.latencyMs ? ' (' + h.latencyMs + ' ms)' : '') +
            (h.missingTables && h.missingTables.length ? ' Missing: ' + h.missingTables.join(', ') : '');
        })
        .catch(function () { out.className = 'hint err'; out.textContent = '✕ Could not reach the dashboard server.'; });
    });
  }

  // ------------------------------------------------------------------ auto refresh
  var auto = $('#autoRefresh');
  var remaining = 0;
  if (auto) {
    auto.value = store('bi.auto') || '0';
    if (!auto.value) auto.value = '0';
    var armed = function () { remaining = parseInt(auto.value, 10) || 0; auto.title = remaining ? 'Reloading every ' + remaining + ' s' : 'Automatic reload is off'; };
    armed();
    auto.addEventListener('change', function () { store('bi.auto', auto.value); armed(); });
    setInterval(function () {
      var every = parseInt(auto.value, 10) || 0;
      if (!every || body.dataset.searched !== 'true' || document.hidden || $('#helpDlg').open) return;
      if (--remaining <= 0) { progress(true); location.reload(); }
    }, 1000);
  }

  // ------------------------------------------------------------------ live elapsed counters
  setInterval(function () {
    var now = Date.now() + skew;
    $$('.live-elapsed').forEach(function (el) {
      var s = parseInt(el.getAttribute('data-start'), 10);
      if (s) el.textContent = fmtDur(Math.max(0, now - s));
    });
  }, 1000);

  // ------------------------------------------------------------------ copy helper
  function copyText(text, btn) {
    var done = function () { if (btn) { btn.classList.add('done'); var old = btn.textContent; btn.textContent = '✓'; setTimeout(function () { btn.classList.remove('done'); btn.textContent = old; }, 900); } };
    if (navigator.clipboard && window.isSecureContext) { navigator.clipboard.writeText(text).then(done, function () { }); return; }
    var ta = document.createElement('textarea');
    ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
    document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); done(); } catch (e) { /* ignore */ }
    ta.remove();
  }

  // ------------------------------------------------------------------ exit message / stack trace viewer
  var FRAMEWORK = /^\s*at\s+(org\.springframework|org\.hibernate|org\.apache|java\.|javax\.|jakarta\.|jdk\.|sun\.|com\.sun\.|oracle\.|com\.zaxxer|io\.netty|reactor\.|org\.eclipse|org\.aspectj|net\.sf\.cglib)/;
  function enhanceExit(pre) {
    if (pre.dataset.enhanced) return;
    pre.dataset.enhanced = '1';
    var raw = pre.textContent;
    var lines = raw.split('\n');
    var hasFw = false, hasAt = false;
    var html = lines.map(function (line) {
      var cls = '';
      if (/^\s*at\s+/.test(line)) { hasAt = true; if (FRAMEWORK.test(line)) { cls = 'at fw'; hasFw = true; } else { cls = 'at app'; } }
      else if (/^\s*Caused by:/.test(line)) cls = 'cause';
      else if (/^[\w$.]+(Exception|Error|Throwable)\b/.test(line) || /\b[\w$.]+(Exception|Error):/.test(line)) cls = 'exc';
      return '<span class="ln ' + cls + '">' + (esc(line) || ' ') + '</span>';
    }).join('');
    var wrap = document.createElement('div');
    wrap.className = 'exit-wrap';
    var tools = '<div class="exit-tools">' +
      (hasFw ? '<button type="button" class="btn small" data-x="fw">Hide framework frames</button>' : '') +
      '<button type="button" class="btn small" data-x="wrap">Wrap</button>' +
      '<button type="button" class="btn small" data-x="copy">Copy</button></div>';
    pre.innerHTML = html;
    pre.parentNode.insertBefore(wrap, pre);
    wrap.innerHTML = tools;
    wrap.appendChild(pre);
    wrap.addEventListener('click', function (ev) {
      var b = ev.target.closest('[data-x]');
      if (!b) return;
      var k = b.getAttribute('data-x');
      if (k === 'fw') { var hide = wrap.classList.toggle('hide-fw'); b.textContent = hide ? 'Show framework frames' : 'Hide framework frames'; }
      else if (k === 'wrap') pre.classList.toggle('wrap');
      else if (k === 'copy') copyText(raw, b);
    });
    if (hasAt && lines.length > 60 && hasFw) { wrap.classList.add('hide-fw'); var fb = $('[data-x="fw"]', wrap); if (fb) fb.textContent = 'Show framework frames'; }
  }

  // ------------------------------------------------------------------ detail pane
  var current = { id: null, db: body.dataset.db };

  function setTab(root, name) {
    $$('.tab', root).forEach(function (t) { var on = t.dataset.tab === name; t.classList.toggle('active', on); t.setAttribute('aria-selected', on ? 'true' : 'false'); });
    $$('.tabpanel', root).forEach(function (p) { p.hidden = p.dataset.panel !== name; });
  }

  function initDetail(root) {
    $$('pre.exit', root).forEach(enhanceExit);

    $$('.tab', root).forEach(function (t) { t.addEventListener('click', function () { setTab(root, t.dataset.tab); }); });

    root.addEventListener('click', function (ev) {
      var c = ev.target.closest('.copy');
      if (c) { copyText(c.getAttribute('data-copy') || '', c); return; }
      var a = ev.target.closest('[data-action]');
      if (a) {
        var act = a.getAttribute('data-action');
        if (act === 'close-detail') closeDetail();
        else if (act === 'reload-detail') openExecution(current.id, true);
        else if (act === 'load-context') loadContext(root, a);
        return;
      }
      var row = ev.target.closest('.step-row');
      if (row && !ev.target.closest('button')) { var more = row.nextElementSibling; if (more) more.classList.toggle('open'); }
    });

    var dataEl = $('#timelineData', root);
    var host = $('#timeline', root);
    if (dataEl && host && window.BatchTimeline) {
      try {
        var data = JSON.parse(dataEl.textContent);
        window.BatchTimeline.mount(host, data, {
          onSelect: function (stepId) {
            $$('.step-row, .step-more', root).forEach(function (r) { r.classList.remove('sel'); });
            var row = $('.step-row[data-step-id="' + stepId + '"]', root);
            if (row) row.classList.add('sel');
          },
          onJump: function (stepId) {
            setTab(root, 'steps');
            var row = $('.step-row[data-step-id="' + stepId + '"]', root);
            if (row) { row.classList.add('sel'); if (row.nextElementSibling) row.nextElementSibling.classList.add('open'); row.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
          }
        });
      } catch (e) {
        host.innerHTML = '<p class="muted">The timeline data could not be read.</p>';
      }
    }
  }

  function loadContext(root, btn) {
    var target = $('#contextTarget', root);
    btn.disabled = true;
    target.innerHTML = '<div class="skeleton"><i></i><i></i></div>';
    fetch('/executions/' + encodeURIComponent(current.id) + '/context?db=' + encodeURIComponent(current.db))
      .then(function (r) { return r.text(); })
      .then(function (html) { target.innerHTML = html; $$('pre.ctx', target).forEach(function (p) { p.dataset.enhanced = '1'; }); btn.textContent = 'Reload context'; btn.disabled = false; })
      .catch(function () { target.innerHTML = '<div class="alert error">Could not load the context.</div>'; btn.disabled = false; });
  }

  function openExecution(id, keepTab) {
    if (!id || !detailPane) return;
    var prevTab = keepTab ? ($('.tab.active', detailPane) || {}).dataset : null;
    var seq = ++detailSeq;
    current.id = id;
    current.db = body.dataset.db;
    $$('.exec').forEach(function (li) { var on = li.dataset.id === String(id); li.classList.toggle('selected', on); li.setAttribute('aria-selected', on ? 'true' : 'false'); });
    var sel = $('#selectedInput'); if (sel) sel.value = id;
    try { var u = new URL(location.href); u.searchParams.set('selected', id); history.replaceState(null, '', u); } catch (e) { /* ignore */ }
    detailPane.innerHTML = '<div class="skeleton" aria-busy="true"><i style="width:60%"></i><i></i><i style="width:80%"></i><i style="width:40%"></i></div>';
    progress(true);
    fetch('/executions/' + encodeURIComponent(id) + '?db=' + encodeURIComponent(current.db), { headers: { Accept: 'text/html' } })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        if (seq !== detailSeq) return;
        detailPane.innerHTML = html;
        var root = $('.d', detailPane);
        if (root) { initDetail(root); if (prevTab && prevTab.tab) setTab(root, prevTab.tab); }
      })
      .catch(function () {
        if (seq !== detailSeq) return;
        detailPane.innerHTML = '<div class="alert error" role="alert"><b>Couldn\'t load this execution.</b><p>The dashboard server did not answer. Your filters and list are unchanged.</p></div>';
      })
      .then(function () { if (seq === detailSeq) progress(false); });
  }

  function closeDetail() {
    detailSeq++;
    current.id = null;
    $$('.exec.selected').forEach(function (li) { li.classList.remove('selected'); li.setAttribute('aria-selected', 'false'); });
    var sel = $('#selectedInput'); if (sel) sel.value = '';
    try { var u = new URL(location.href); u.searchParams.delete('selected'); history.replaceState(null, '', u); } catch (e) { /* ignore */ }
    detailPane.innerHTML = '<div class="placeholder"><h3>Select an execution</h3><p>Click a row to see its steps, timeline, parameters and exit messages.</p></div>';
  }

  var list = $('#execList');
  if (list) {
    list.addEventListener('click', function (ev) { var li = ev.target.closest('.exec'); if (li) openExecution(li.dataset.id); });
    list.addEventListener('keydown', function (ev) {
      var li = ev.target.closest('.exec');
      if (li && (ev.key === 'Enter' || ev.key === ' ')) { ev.preventDefault(); openExecution(li.dataset.id); }
    });
    var pre = body.dataset.selected;
    if (pre && $('.exec[data-id="' + pre + '"]')) openExecution(pre);
  }

  // ------------------------------------------------------------------ keyboard shortcuts
  document.addEventListener('keydown', function (ev) {
    if (ev.ctrlKey || ev.metaKey || ev.altKey) return;
    var dlg = $('#helpDlg');
    if (ev.key === 'Escape') { if (dlg.open) dlg.close(); else if (current.id && !isTyping(ev.target)) closeDetail(); return; }
    if (isTyping(ev.target)) return;
    var items = $$('.exec');
    var focused = items.indexOf(document.activeElement);
    switch (ev.key) {
      case '/': ev.preventDefault(); $('#jobName').focus(); $('#jobName').select(); break;
      case '?': dlg.showModal(); break;
      case 'r': progress(true); location.reload(); break;
      case 't': toggleTheme(); break;
      case 'j': if (items.length) { ev.preventDefault(); items[Math.min(items.length - 1, focused < 0 ? 0 : focused + 1)].focus(); } break;
      case 'k': if (items.length) { ev.preventDefault(); items[Math.max(0, focused < 0 ? 0 : focused - 1)].focus(); } break;
      case '1': case '2': case '3': case '4':
        var root = $('.d', detailPane);
        if (root) setTab(root, ['overview', 'steps', 'params', 'context'][+ev.key - 1]);
        break;
      default: break;
    }
  });

  window.addEventListener('pageshow', function () { progress(false); });
})();
