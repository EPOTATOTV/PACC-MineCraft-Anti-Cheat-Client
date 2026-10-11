/* PACC 下载站 —— 平台中心渲染
 *
 * 读 window.PACC_PLATFORMS 目录与 window.PACC_CONFIG 版本信息，渲染分类标签页、
 * 渠道卡片与 Forge/Fabric 版本矩阵。界面文案统一走 window.PACC_LANG（i18n 层），
 * 渠道自身的中英双语字段按当前语言取值；本文件不写用户可见的字面量。
 * 语言切换（pacc:lang）时整体重渲染。
 */
(function () {
  'use strict';

  var CATEGORIES = ['client', 'server', 'mod', 'extension', 'config'];
  var catalog = window.PACC_PLATFORMS || [];
  var cfg = window.PACC_CONFIG || {};

  var tabsEl = null;
  var gridEl = null;
  var matrixEl = null;
  var active = 'all';
  var animate = true; // 首次入场做交错动画，之后（含切语言）直接呈现

  function lang() {
    return (window.PACC_LANG && window.PACC_LANG.current()) || 'zh-CN';
  }

  function t(key) {
    return window.PACC_LANG ? window.PACC_LANG.t(key) : null;
  }

  /* 取双语字段：缺当前语言回退中文，再回退首个可用值 */
  function pick(field) {
    if (field == null) return '';
    if (typeof field === 'string') return field;
    var l = lang();
    if (field[l] != null) return field[l];
    if (field['zh-CN'] != null) return field['zh-CN'];
    var keys = Object.keys(field);
    return keys.length ? field[keys[0]] : '';
  }

  function channelVersion(channel) {
    if (typeof cfg.versionFor === 'function') return cfg.versionFor(channel.id);
    return cfg.PACC_VERSION || '';
  }

  function substitute(pattern, channel, mc) {
    var out = String(pattern).replace(/\{ver\}/g, channelVersion(channel));
    if (mc != null) out = out.replace(/\{mc\}/g, mc);
    return out;
  }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  /* 与 site.js 相同的复制实现：优先 Clipboard API，非安全上下文回退临时选区 */
  function copyText(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.top = '-1000px';
      document.body.appendChild(ta);
      ta.select();
      var ok = false;
      try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
      document.body.removeChild(ta);
      ok ? resolve() : reject(new Error('copy failed'));
    });
  }

  function makeCard(channel, index) {
    var card = el('article', 'pc-card' + (animate ? '' : ' is-in'));
    card.dataset.cat = channel.category;
    card.dataset.id = channel.id;
    card.style.setProperty('--pc-i', String(index));

    var head = el('div', 'pc-card-head');
    head.appendChild(el('div', 'pc-badge mono', channel.badge || channel.category.toUpperCase()));
    head.appendChild(el('h3', 'pc-name', pick(channel.name)));
    card.appendChild(head);

    if (channel.platforms && channel.platforms.length) {
      var tags = el('div', 'pc-tags');
      channel.platforms.forEach(function (p) { tags.appendChild(el('span', 'pc-tag mono', p)); });
      card.appendChild(tags);
    }

    var mcForPattern = channel.mcVersions && channel.mcVersions.length ? channel.mcVersions[0] : null;
    card.appendChild(el('div', 'pc-artifact mono', substitute(channel.artifactPattern, channel, mcForPattern)));

    var upd = el('p', 'pc-update');
    upd.appendChild(el('span', 'pc-lbl', t('pc.label.update')));
    upd.appendChild(document.createTextNode(' ' + pick(channel.updateMethod)));
    card.appendChild(upd);

    var guide = el('details', 'pc-guide');
    guide.appendChild(el('summary', null, t('pc.label.guide')));
    var steps = pick(channel.installGuide);
    var list = el('ol', 'pc-guide-list');
    (Array.isArray(steps) ? steps : [steps]).forEach(function (s) {
      list.appendChild(el('li', null, s));
    });
    guide.appendChild(list);
    card.appendChild(guide);

    var hasHash = typeof channel.sha256 === 'string' && channel.sha256.length > 0;
    var foot = el('div', 'pc-foot');
    foot.appendChild(el('code', 'pc-sha mono', hasHash ? channel.sha256 : (t('pc.sha.pending') || '')));

    var btn = el('button', 'copy-btn', t('pc.copy'));
    btn.type = 'button';
    var ariaTpl = t('pc.copy.aria') || '';
    btn.setAttribute('aria-label', ariaTpl.indexOf('%s') > -1 ? ariaTpl.replace('%s', pick(channel.name)) : pick(channel.name));
    btn.addEventListener('click', function () { onCopy(btn, channel, hasHash); });
    foot.appendChild(btn);
    card.appendChild(foot);

    return card;
  }

  function onCopy(btn, channel, hasHash) {
    var reset = function () {
      btn.textContent = t('pc.copy');
      btn.classList.remove('copied');
    };
    if (!hasHash) {
      btn.textContent = t('pc.copyFail') || '';
      setTimeout(reset, 1600);
      return;
    }
    copyText(channel.sha256).then(function () {
      btn.textContent = t('pc.copied');
      btn.classList.add('copied');
      setTimeout(reset, 1600);
    }, function () {
      btn.textContent = t('pc.copyFail');
      setTimeout(reset, 1600);
    });
  }

  function renderTabs() {
    tabsEl.textContent = '';
    var defs = [{ id: 'all', key: 'pc.cat.all' }].concat(
      CATEGORIES.map(function (cat) { return { id: cat, key: 'pc.cat.' + cat }; })
    );
    defs.forEach(function (d) {
      var btn = el('button', 'pc-tab', t(d.key) || d.id);
      btn.type = 'button';
      btn.id = 'pc-tab-' + d.id;
      btn.dataset.cat = d.id;
      btn.setAttribute('role', 'tab');
      btn.setAttribute('aria-controls', 'pc-grid');
      var on = d.id === active;
      btn.setAttribute('aria-selected', on ? 'true' : 'false');
      btn.tabIndex = on ? 0 : -1;
      btn.addEventListener('click', function () { select(d.id); });
      tabsEl.appendChild(btn);
    });
  }

  function renderMatrix() {
    matrixEl.textContent = '';
    var mods = catalog.filter(function (c) {
      return c.category === 'mod' && c.mcVersions && c.mcVersions.length;
    });
    if (!mods.length) { matrixEl.hidden = true; return; }

    var head = el('div', 'pc-matrix-head');
    head.appendChild(el('h3', 'pc-matrix-title', t('pc.matrix.title')));
    head.appendChild(el('span', 'tag mono', t('pc.matrix.tag') || 'MATRIX'));
    matrixEl.appendChild(head);
    matrixEl.appendChild(el('p', 'sub', t('pc.matrix.sub')));

    var table = el('table', 'pc-matrix-table');
    var thead = el('thead');
    var htr = el('tr');
    [t('pc.matrix.loader'), t('pc.matrix.mc'), t('pc.matrix.pacc'), t('pc.matrix.file')].forEach(function (h) {
      htr.appendChild(el('th', 'mono', h));
    });
    thead.appendChild(htr);
    table.appendChild(thead);

    var tbody = el('tbody');
    mods.forEach(function (c) {
      c.mcVersions.forEach(function (mc) {
        var tr = el('tr');
        tr.appendChild(el('td', null, pick(c.name)));
        tr.appendChild(el('td', 'mono', mc));
        tr.appendChild(el('td', 'mono', channelVersion(c)));
        tr.appendChild(el('td', 'mono pc-matrix-file', substitute(c.artifactPattern, c, mc)));
        tbody.appendChild(tr);
      });
    });
    table.appendChild(tbody);
    matrixEl.appendChild(table);
  }

  function applyFilter() {
    gridEl.querySelectorAll('.pc-card').forEach(function (card) {
      card.hidden = !(active === 'all' || card.dataset.cat === active);
    });
    gridEl.setAttribute('aria-labelledby', 'pc-tab-' + active);
    if (matrixEl) matrixEl.hidden = !(active === 'all' || active === 'mod');
  }

  function select(cat) {
    active = cat;
    tabsEl.querySelectorAll('.pc-tab').forEach(function (btn) {
      var on = btn.dataset.cat === active;
      btn.setAttribute('aria-selected', on ? 'true' : 'false');
      btn.tabIndex = on ? 0 : -1;
    });
    applyFilter();
  }

  function onTabKeys(e) {
    if (['ArrowRight', 'ArrowLeft', 'Home', 'End'].indexOf(e.key) === -1) return;
    var tabs = Array.prototype.slice.call(tabsEl.querySelectorAll('.pc-tab'));
    var idx = tabs.indexOf(document.activeElement);
    if (idx === -1) return;
    e.preventDefault();
    if (e.key === 'ArrowRight') idx = (idx + 1) % tabs.length;
    else if (e.key === 'ArrowLeft') idx = (idx - 1 + tabs.length) % tabs.length;
    else if (e.key === 'Home') idx = 0;
    else idx = tabs.length - 1;
    select(tabs[idx].dataset.cat);
    tabs[idx].focus();
  }

  function wireReveal() {
    var cards = gridEl.querySelectorAll('.pc-card');
    var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (!animate || reduced || typeof IntersectionObserver !== 'function') {
      cards.forEach(function (c) { c.classList.add('is-in'); });
      return;
    }
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('is-in');
        io.unobserve(entry.target);
      });
    }, { threshold: 0.08, rootMargin: '0px 0px -10% 0px' });
    cards.forEach(function (c) { io.observe(c); });
  }

  function renderAll() {
    if (!gridEl) return;
    renderTabs();
    gridEl.textContent = '';
    catalog.forEach(function (channel, i) { gridEl.appendChild(makeCard(channel, i)); });
    renderMatrix();
    applyFilter();
    wireReveal();
  }

  function boot() {
    tabsEl = document.getElementById('pc-tabs');
    gridEl = document.getElementById('pc-grid');
    matrixEl = document.getElementById('pc-matrix');
    if (!tabsEl || !gridEl || !matrixEl || !catalog.length) return;

    tabsEl.addEventListener('keydown', onTabKeys);
    renderAll();

    document.addEventListener('pacc:lang', function () {
      animate = false;
      renderAll();
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
