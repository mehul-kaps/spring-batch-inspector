/* Step timeline (Gantt) - draws persisted step start/end timestamps on a real time axis. No dependencies. */
(function () {
  'use strict';

  var LW_DEFAULT = 170; // label column width (px)
  var NICE = [1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000, 600000,
    900000, 1800000, 3600000, 7200000, 10800000, 21600000, 43200000, 86400000, 172800000, 604800000];
  var ZOOMS = [1, 1.5, 2, 3, 4, 6, 8, 12, 16, 24];

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function fmtDur(ms) {
    if (ms == null) return '—';
    ms = Math.max(0, ms);
    if (ms < 1000) return Math.round(ms) + ' ms';
    if (ms < 60000) return (ms / 1000).toFixed(1) + ' s';
    var s = Math.floor(ms / 1000), d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    var p2 = function (n) { return (n < 10 ? '0' : '') + n; };
    if (d > 0) return d + 'd ' + p2(h) + 'h ' + p2(m) + 'm';
    if (h > 0) return h + 'h ' + p2(m) + 'm ' + p2(sec) + 's';
    return m + 'm ' + p2(sec) + 's';
  }

  function fmtOffset(ms) {
    if (ms <= 0) return '0';
    if (ms < 1000) return '+' + ms + 'ms';
    var s = ms / 1000;
    if (s < 60) return '+' + (Math.round(s * 10) / 10) + 's';
    var m = Math.floor(s / 60), sec = Math.round(s % 60);
    if (m < 60) return '+' + m + 'm' + (sec ? ' ' + sec + 's' : '');
    var h = Math.floor(m / 60), mm = m % 60;
    return '+' + h + 'h' + (mm ? ' ' + mm + 'm' : '');
  }

  function makeFormatters(zone) {
    var time, date;
    try {
      time = new Intl.DateTimeFormat('en-GB', { timeZone: zone, hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
      date = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' });
    } catch (e) {
      time = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
      date = new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit' });
    }
    return { time: function (ms) { return time.format(new Date(ms)); }, date: function (ms) { return date.format(new Date(ms)); } };
  }

  var tip = null;
  function tooltip() {
    if (!tip) {
      tip = document.createElement('div');
      tip.className = 'tooltip';
      tip.hidden = true;
      document.body.appendChild(tip);
    }
    return tip;
  }

  function barDuration(b, now) {
    if (b.inProgress) return Math.max(0, now - b.startMs);
    return b.durationMs;
  }

  function mount(host, data, hooks) {
    hooks = hooks || {};
    host.innerHTML = '<div class="tl-main"></div><div class="tl-inspector" hidden></div>';
    var main = host.querySelector('.tl-main');
    var inspector = host.querySelector('.tl-inspector');
    var fm = makeFormatters(data.zoneId);
    var skew = data.nowMs - Date.now();
    var state = { zoom: 1, selected: null };
    var live = data.running || data.bars.some(function (b) { return b.inProgress; });

    if (!data.bars.length) {
      main.innerHTML = '<p class="muted">No step has a start timestamp yet, so there is nothing to draw.</p>' + untimedHtml();
      return;
    }

    function untimedHtml() {
      if (!data.untimed || !data.untimed.length) return '';
      return '<p class="tl-untimed">Steps without a start timestamp (not drawn): ' + data.untimed.map(esc).join(', ') + '</p>';
    }

    function render() {
      var now = Date.now() + skew;
      var bars = data.bars.map(function (b) {
        var c = Object.assign({}, b);
        if (b.inProgress) c.endMs = Math.max(now, b.startMs);
        return c;
      }).sort(function (a, b) {
        if (a.startMs !== b.startMs) return a.startMs - b.startMs;
        if (a.endMs !== b.endMs) return a.endMs - b.endMs;
        return a.stepId - b.stepId;
      });

      // Scale step rows to the actual step activity window. This keeps quick sequential
      // steps visible instead of shrinking them against long-running job idle time.
      var LW = LW_DEFAULT;
      if (bars.length) {
        var longestName = bars.reduce(function (n, b) { return Math.max(n, (b.name || '').length); }, 0);
        LW = Math.max(150, Math.min(210, longestName > 22 ? 210 : longestName > 18 ? 190 : 170));
      }
      var t0 = bars.reduce(function (min, b) { return Math.min(min, b.startMs); }, bars.length ? bars[0].startMs : data.startMs);
      var t1 = bars.reduce(function (max, b) { return Math.max(max, b.endMs); }, t0);
      if (data.running) t1 = Math.max(t1, now);
      bars.forEach(function (b) { t1 = Math.max(t1, b.endMs); });
      var span = Math.max(1, t1 - t0);

      var prev = main.querySelector('.tl-scroll');
      var scrollLeft = prev ? prev.scrollLeft : 0;

      var viewW = host.clientWidth || 720;
      var trackBase = Math.max(320, viewW - LW - 6);
      var trackW = Math.round(trackBase * state.zoom);
      var x = function (ms) { return (ms - t0) / span * trackW; };

      // ---- axis ticks
      var target = Math.max(3, Math.floor(trackW / 120));
      var rough = span / target;
      var step = NICE[NICE.length - 1];
      for (var i = 0; i < NICE.length; i++) { if (NICE[i] >= rough) { step = NICE[i]; break; } }
      var ticks = '', grid = '';
      var lastDate = null;
      for (var o = 0; o <= span; o += step) {
        var left = x(t0 + o);
        var abs = t0 + o;
        var d = fm.date(abs);
        var clock = fm.time(abs) + (d !== lastDate && (lastDate !== null || span > 86400000) ? ' ' + d.slice(5) : '');
        lastDate = d;
        ticks += '<div class="tl-tick" style="left:' + left.toFixed(1) + 'px">' + esc(fmtOffset(o)) + '<em>' + esc(clock) + '</em></div>';
        grid += '<i style="left:' + left.toFixed(1) + 'px"></i>';
        if (o > 1e12) break;
      }

      // ---- job row
      var js = String(data.jobStatus || '').toLowerCase();
      var jobCss = ['failed', 'completed', 'started', 'starting', 'stopping', 'stopped', 'abandoned'].indexOf(js) >= 0 ? js : 'unknown';
      var html = '<div class="tl-scroll"><div class="tl-inner" style="width:' + (LW + trackW) + 'px;--lw:' + LW + 'px">';
      html += '<div class="tl-row head"><div class="tl-label">Step <span style="margin-left:auto">time →</span></div><div class="tl-track" style="width:' + trackW + 'px">' + ticks + '</div></div>';
      html += '<div class="tl-grid" style="left:' + LW + 'px;width:' + trackW + 'px">' + grid + '</div>';
      html += '<div class="tl-row s-' + jobCss + '"><div class="tl-label" title="Whole job execution"><i class="dot"></i><span class="nm">JOB EXECUTION</span><span class="pc">' + esc(fmtDur(span)) + '</span></div>' +
        '<div class="tl-track" style="width:' + trackW + 'px"><div class="tl-job s-' + jobCss + '" style="left:0;width:' + trackW + 'px"></div></div></div>';

      // ---- idle gaps
      if (data.gaps && data.gaps.length) {
        var g = '';
        data.gaps.forEach(function (gp) {
          var fromMs = Math.max(gp.fromMs, t0);
          var toMs = Math.min(gp.toMs, t1);
          if (toMs <= fromMs) return;
          var gl = x(fromMs), gw = Math.max(2, x(toMs) - gl);
          g += '<div class="tl-gap" style="left:' + gl.toFixed(1) + 'px;width:' + gw.toFixed(1) + 'px" title="Nothing running for ' + esc(fmtDur(gp.toMs - gp.fromMs)) + '"></div>';
          if (gw > 70) g += '<span class="tl-gaplbl" style="left:' + (gl + 4).toFixed(1) + 'px">idle ' + esc(fmtDur(toMs - fromMs)) + '</span>';
        });
        if (g) html += '<div class="tl-row"><div class="tl-label" title="Time where no step was running according to the stored timestamps"><i class="dot" style="background:var(--muted)"></i><span class="nm">idle gaps</span></div><div class="tl-track" style="width:' + trackW + 'px">' + g + '</div></div>';
      }

      // ---- step rows
      bars.forEach(function (b, idx) {
        var left = x(b.startMs);
        var w = Math.max(8, x(b.endMs) - left);
        var dur = barDuration(b, now);
        var startOffset = fmtOffset(b.startMs - t0);
        var endOffset = b.endMs != null ? fmtOffset(b.endMs - t0) : 'now';
        var cls = 'tl-step s-' + b.statusCss + (b.inProgress ? ' running' : '') + (b.endUnknown ? ' unknown-end' : '') +
          (b.zeroDuration ? ' zero' : '') + (b.parallel ? ' parallel' : '') + (state.selected === b.stepId ? ' sel' : '');
        var inner = (w > 54 && dur != null) ? esc(fmtDur(dur)) : '';
        html += '<div class="tl-row s-' + b.statusCss + '">' +
          '<div class="tl-label" data-i="' + idx + '" title="' + esc(b.name) + '"><span class="tl-order">' + (idx + 1) + '</span><i class="dot"></i><span class="nm">' + esc(b.name) + '</span>' +
          (b.slowRank === 1 ? '<span class="rank" title="Slowest step">SLOWEST</span>' : (b.slowRank > 1 ? '<span class="rank" title="Among the slowest steps">#' + b.slowRank + '</span>' : '')) +
          (b.parallel ? '<span class="pc" title="Overlaps another step in time">∥</span>' : '') +
          '<span class="pc" title="start ' + esc(b.startText) + ' · end ' + esc(endOffset) + ' · duration ' + esc(dur == null ? 'unknown' : fmtDur(dur)) + '">' + esc(startOffset) + '</span></div>' +
          '<div class="tl-track" style="width:' + trackW + 'px"><button type="button" class="' + cls + '" data-i="' + idx + '" style="left:' + left.toFixed(1) + 'px;width:' + w.toFixed(1) + 'px" ' +
          'aria-label="' + esc(b.name + ', ' + b.statusLabel + ', ' + (dur == null ? 'end unknown' : fmtDur(dur))) + '">' + inner + '</button></div></div>';
      });

      if (data.running) {
        html += '<div class="tl-now" style="left:' + (LW + x(now)).toFixed(1) + 'px"></div>';
      }
      html += '</div></div>';

      // ---- stats / controls / legend
      var parallel = bars.filter(function (b) { return b.parallel; }).length;
      var idle = (data.gaps || []).reduce(function (a, gp) { return a + (gp.toMs - gp.fromMs); }, 0);
      var slow = bars.filter(function (b) { return b.slowRank === 1; })[0];
      var stats = '<div class="tl-bar">' +
        '<span class="stat">window <b>' + esc(fmtDur(span)) + '</b></span>' +
        '<span class="stat">steps <b>' + bars.length + '</b></span>' +
        (parallel ? '<span class="stat">overlapping <b>' + parallel + '</b></span>' : '') +
        (idle > 0 ? '<span class="stat">idle <b>' + esc(fmtDur(idle)) + '</b></span>' : '') +
        (slow ? '<span class="stat">slowest <b>' + esc(slow.name) + '</b></span>' : '') +
        '<span class="tl-ctl"><button class="btn small" data-zoom="out" title="Zoom out">−</button><span class="stat"><b>' + state.zoom + '×</b></span>' +
        '<button class="btn small" data-zoom="in" title="Zoom in">+</button><button class="btn small" data-zoom="fit" title="Fit to width">Fit</button></span></div>';

      var seen = {}, legend = '';
      bars.forEach(function (b) { if (!seen[b.statusCss]) { seen[b.statusCss] = 1; legend += '<span class="s-' + b.statusCss + '"><i></i>' + esc(b.statusLabel) + '</span>'; } });
      legend += '<span>▨ running (extends to now)</span><span>┆ end not recorded</span><span>◆ zero duration</span><span>▭ ring = overlaps another step</span><span>┈ idle gap</span>';

      main.innerHTML = stats + html + '<div class="tl-legend">' + legend + '</div>' + untimedHtml();
      var sc = main.querySelector('.tl-scroll');
      if (sc) sc.scrollLeft = scrollLeft;
      main._bars = bars;
      main._now = now;
    }

    function inspect(b) {
      var now = Date.now() + skew;
      var dur = barDuration(b, now);
      inspector.hidden = false;
      inspector.className = 'tl-inspector s-' + b.statusCss;
      inspector.innerHTML = '<h4><span class="badge s-' + b.statusCss + '"><i></i>' + esc(b.statusLabel) + '</span> ' + esc(b.name) +
        ' <span class="muted">#' + b.stepId + '</span></h4>' +
        '<div class="kv"><span>start <b>' + esc(b.startText) + '</b></span><span>end <b>' + esc(b.endText) + '</b></span>' +
        '<span>duration <b>' + esc(dur == null ? 'end not recorded' : fmtDur(dur)) + '</b></span></div>' +
        '<div class="kv"><span>' + esc(b.counters) + '</span></div>' +
        '<div class="kv"><span>exit code <b>' + esc(b.exitCode || '—') + '</b></span></div>' +
        (b.exitMessage ? '<pre>' + esc(b.exitMessage) + '</pre>' : '') +
        '<div style="margin-top:6px"><button class="btn small" data-jump="' + b.stepId + '">Open in Steps tab</button></div>';
    }

    function select(idx) {
      var b = main._bars[idx];
      if (!b) return;
      state.selected = b.stepId;
      main.querySelectorAll('.tl-step').forEach(function (el) { el.classList.toggle('sel', el.getAttribute('data-i') === String(idx)); });
      inspect(b);
      if (hooks.onSelect) hooks.onSelect(b.stepId);
    }

    function showTip(ev, idx) {
      var b = main._bars[idx];
      if (!b) return;
      var dur = barDuration(b, Date.now() + skew);
      var t = tooltip();
      t.style.setProperty('--c', getComputedStyle(ev.currentTarget).backgroundColor);
      t.innerHTML = '<b>' + esc(b.name) + '</b> <span class="dim">#' + b.stepId + '</span><br>' +
        esc(b.statusLabel) + (b.parallel ? ' · runs in parallel with another step' : '') + '<br>' +
        '<span class="dim">start</span> ' + esc(b.startText) + '<br><span class="dim">end&nbsp;&nbsp;</span> ' + esc(b.endText) + '<br>' +
        '<span class="dim">took</span> <b>' + esc(dur == null ? 'unknown (end not recorded)' : fmtDur(dur)) + '</b><br>' +
        '<span class="dim">' + esc(b.counters) + '</span>' +
        (b.exitCode ? '<br><span class="dim">exit</span> ' + esc(b.exitCode) : '') +
        (b.exitMessage ? '<pre>' + esc(b.exitMessage.split('\n').slice(0, 3).join('\n')) + '</pre>' : '');
      t.hidden = false;
      moveTip(ev);
    }
    function moveTip(ev) {
      var t = tooltip();
      var x = ev.clientX + 14, y = ev.clientY + 16;
      var r = t.getBoundingClientRect();
      if (x + r.width > window.innerWidth - 8) x = Math.max(8, ev.clientX - r.width - 14);
      if (y + r.height > window.innerHeight - 8) y = Math.max(8, ev.clientY - r.height - 12);
      t.style.left = x + 'px';
      t.style.top = y + 'px';
    }
    function hideTip() { tooltip().hidden = true; }

    host.addEventListener('mouseover', function (ev) {
      var el = ev.target.closest('.tl-step');
      if (el) showTip({ currentTarget: el, clientX: ev.clientX, clientY: ev.clientY }, +el.getAttribute('data-i'));
    });
    host.addEventListener('mousemove', function (ev) { if (ev.target.closest('.tl-step')) moveTip(ev); });
    host.addEventListener('mouseout', function (ev) { if (ev.target.closest('.tl-step')) hideTip(); });
    host.addEventListener('focusin', function (ev) {
      var el = ev.target.closest && ev.target.closest('.tl-step');
      if (el) { var r = el.getBoundingClientRect(); showTip({ currentTarget: el, clientX: r.left, clientY: r.bottom }, +el.getAttribute('data-i')); }
    });
    host.addEventListener('focusout', hideTip);
    host.addEventListener('click', function (ev) {
      var z = ev.target.closest('[data-zoom]');
      if (z) {
        var i = ZOOMS.indexOf(state.zoom);
        if (z.getAttribute('data-zoom') === 'in') state.zoom = ZOOMS[Math.min(ZOOMS.length - 1, i + 1)];
        else if (z.getAttribute('data-zoom') === 'out') state.zoom = ZOOMS[Math.max(0, i - 1)];
        else state.zoom = 1;
        render();
        return;
      }
      var j = ev.target.closest('[data-jump]');
      if (j) { if (hooks.onJump) hooks.onJump(+j.getAttribute('data-jump')); return; }
      var el = ev.target.closest('[data-i]');
      if (el) select(+el.getAttribute('data-i'));
    });

    render();
    var resizeTimer;
    window.addEventListener('resize', function () { clearTimeout(resizeTimer); resizeTimer = setTimeout(function () { if (host.isConnected) render(); }, 150); });

    if (live) {
      var timer = setInterval(function () {
        if (!host.isConnected) { clearInterval(timer); return; }
        if (!document.hidden && tooltip().hidden) render();
      }, 1000);
    }
    return { select: select };
  }

  window.BatchTimeline = { mount: mount, fmtDur: fmtDur };
})();
