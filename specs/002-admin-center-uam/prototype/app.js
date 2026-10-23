/* Admin Center 界面设计原型 — 交互与假数据。
 * 仅用于评审：所有数据都在内存里，刷新即重置；不发任何网络请求；不是生产代码，也不会被实现复用。
 * 场景通过查询参数 ?scenario=… 切换（见 README.md）。 */
(function () {
  'use strict';

  var params = new URLSearchParams(location.search);
  var scenario = params.get('scenario') || 'normal';
  var NOW = new Date('2026-10-23T14:30:00');
  var OP_USER = 'u1';
  var OP_SESSION = 's1';
  var DEMO_PW = 'Passw0rd-demo1';
  var PAGE_SIZE = 6;

  /* ---------- 工具 ---------- */
  var root = document.getElementById('app');
  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function pad(n) { return String(n).padStart(2, '0'); }
  function fmt(d) {
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  }
  function minsAgo(m) { return new Date(NOW.getTime() - m * 60000); }
  function daysAgo(d) { return new Date(NOW.getTime() - d * 86400000); }
  function rel(m) {
    if (m < 1) return '刚刚';
    if (m < 60) return m + ' 分钟前';
    if (m < 1440) return Math.floor(m / 60) + ' 小时前';
    return Math.floor(m / 1440) + ' 天前';
  }
  function strong(pw) { return pw.length >= 10 && /[A-Za-z]/.test(pw) && /\d/.test(pw); }
  var nextId = 100;

  /* ---------- 假数据（合成） ---------- */
  function acc(id, loginName, days, status) {
    return { id: id, loginName: loginName, kind: 'PASSWORD', status: status || 'ACTIVE', created: daysAgo(days) };
  }
  var users = [
    { id: 'u1', name: '张三', status: 'ACTIVE', created: daysAgo(52), accounts: [acc('a1', 'zhangsan', 52)] },
    { id: 'u2', name: '李四', status: 'ACTIVE', created: daysAgo(40), accounts: [acc('a2', 'lisi', 40), acc('a3', 'lisi.mobile', 31)] },
    { id: 'u3', name: '王五', status: 'ACTIVE', created: daysAgo(33), accounts: [acc('a4', 'wangwu', 33)] },
    { id: 'u4', name: '赵六', status: 'DISABLED', created: daysAgo(30), accounts: [acc('a5', 'zhaoliu', 30)] },
    { id: 'u5', name: '孙七', status: 'ACTIVE', created: daysAgo(21), accounts: [] },
    { id: 'u6', name: '周八', status: 'ACTIVE', created: daysAgo(19), accounts: [acc('a6', 'zhouba', 19)] },
    { id: 'u7', name: '吴九', status: 'ACTIVE', created: daysAgo(14), accounts: [acc('a7', 'wujiu', 14)] },
    { id: 'u8', name: '郑十', status: 'ACTIVE', created: daysAgo(11), accounts: [acc('a8', 'zhengshi', 11)] },
    { id: 'u9', name: '钱十一', status: 'DISABLED', created: daysAgo(9), accounts: [acc('a9', 'qian11', 9)] },
    { id: 'u10', name: '冯十二', status: 'ACTIVE', created: daysAgo(6), accounts: [acc('a10', 'feng12', 6)] },
    { id: 'u11', name: '陈十三', status: 'ACTIVE', created: daysAgo(3), accounts: [acc('a11', 'chen13', 3)] },
    { id: 'u12', name: '褚十四', status: 'ACTIVE', created: daysAgo(1), accounts: [acc('a12', 'chu14', 1)] }
  ];
  var sessions = [
    { ref: 's1', userId: 'u1', accountId: 'a1', loginMin: 95, activeMin: 0 },
    { ref: 's2', userId: 'u2', accountId: 'a2', loginMin: 200, activeMin: 3 },
    { ref: 's3', userId: 'u2', accountId: 'a3', loginMin: 50, activeMin: 12 },
    { ref: 's4', userId: 'u3', accountId: 'a4', loginMin: 20, activeMin: 7 }
  ];
  if (scenario === 'empty') { users = []; sessions = []; }

  var state = {
    authed: scenario !== 'session-ended' && scenario !== 'force-logged-out',
    notice: scenario === 'session-ended' ? 'ended' : scenario === 'force-logged-out' ? 'terminated' : null,
    loginFails: 0, pwFails: 0,
    userMenu: false, drawer: false, dialog: null, toast: null, restoreFocus: null,
    errors: {}, values: {}, formError: null
  };

  function findUser(id) { return users.filter(function (u) { return u.id === id; })[0]; }
  function userSessions(id) { return sessions.filter(function (s) { return s.userId === id; }); }
  function accountOf(userId, accountId) {
    var u = findUser(userId);
    return u ? u.accounts.filter(function (a) { return a.id === accountId; })[0] : null;
  }
  function accountExists(name) {
    var n = name.toLowerCase();
    return users.some(function (u) { return u.accounts.some(function (a) { return a.loginName.toLowerCase() === n; }); });
  }
  function expiresAt(s) {
    var idle = NOW.getTime() - s.activeMin * 60000 + 30 * 60000;
    var abs = NOW.getTime() - s.loginMin * 60000 + 12 * 3600000;
    return new Date(Math.min(idle, abs));
  }
  function fakeRef(ref) { return '3f9c' + ref + '-71a2-4c0e-9b1d-' + ref + 'e5a8d0c4'; }

  /* ---------- 图标 ---------- */
  function svg(path) { return '<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">' + path + '</svg>'; }
  var I = {
    users: svg('<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>'),
    sessions: svg('<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>'),
    menu: svg('<path d="M4 6h16M4 12h16M4 18h16"/>'),
    key: svg('<circle cx="8" cy="15" r="4"/><path d="M10.8 12.2 20 3M16 7l3 3"/>'),
    out: svg('<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/>'),
    chev: svg('<path d="m9 6 6 6-6 6"/>')
  };

  /* ---------- 路由 ---------- */
  function parseRoute() {
    var h = (location.hash || '').replace(/^#/, '') || (state.authed ? '/users' : '/login');
    var parts = h.split('?');
    return { path: parts[0], q: new URLSearchParams(parts[1] || '') };
  }
  function go(path) { location.hash = '#' + path; }

  /* ---------- 视图片段 ---------- */
  function statusBadge(s) {
    return s === 'ACTIVE' ? '<span class="badge badge-ok">正常</span>' : '<span class="badge badge-off">已停用</span>';
  }
  function field(id, label, opts) {
    opts = opts || {};
    var err = state.errors[id];
    var val = opts.type === 'password' ? '' : (state.values[id] || '');
    return '<div class="field"><label for="' + id + '">' + label + '</label>' +
      '<input id="' + id + '" name="' + id + '" type="' + (opts.type || 'text') + '" value="' + esc(val) + '"' +
      (opts.autocomplete ? ' autocomplete="' + opts.autocomplete + '"' : '') +
      (err ? ' aria-invalid="true" aria-describedby="' + id + '-msg"' : (opts.hint ? ' aria-describedby="' + id + '-msg"' : '')) + '>' +
      (err ? '<span class="err" id="' + id + '-msg">' + esc(err) + '</span>' : (opts.hint ? '<span class="hint" id="' + id + '-msg">' + opts.hint + '</span>' : '')) +
      '</div>';
  }
  function errorState() {
    return '<div class="card"><div class="state"><strong>服务暂不可用</strong>暂时无法获取数据，请稍后重试。你的登录状态已保留。' +
      '<br><button class="btn" data-action="retry">重试</button></div></div>';
  }
  function notFoundUser() {
    return '<div class="card"><div class="state"><strong>用户不存在</strong>该用户可能已被移除，或链接有误。<br>' +
      '<a class="btn" href="#/users">返回用户列表</a></div></div>';
  }

  /* ---------- 页面 ---------- */
  function loginView() {
    var notice = '';
    if (state.notice === 'ended') notice = '<div class="alert alert-warn" role="status"><span class="grow">会话已结束，请重新登录。</span></div>';
    if (state.notice === 'terminated') notice = '<div class="alert alert-warn" role="status"><span class="grow">你的会话已被管理员强制下线，请重新登录。</span></div>';
    var err = state.formError ? '<div class="alert alert-err" role="alert"><span class="grow">' + esc(state.formError) + '</span></div>' : '';
    document.title = '登录 · Admin Center 原型';
    return '<main class="auth"><div class="card">' +
      '<h1>Admin Center</h1><p class="sub muted">请使用账号登录</p>' + notice + err +
      '<form data-form="login" novalidate>' +
      field('accountName', '账号名', { autocomplete: 'username' }) +
      field('password', '密码', { type: 'password', autocomplete: 'current-password' }) +
      '<button class="btn btn-primary" type="submit">登录</button></form>' +
      '<p class="demo-note">原型演示：账号名 <code>zhangsan</code>，密码 <code>' + DEMO_PW + '</code>。' +
      '输入错误会显示统一的失败提示；连续错误 5 次后会显示限流提示。</p>' +
      '</div></main>';
  }

  function usersView(r) {
    var head = function (n) {
      return '<div class="page-head"><div class="grow"><h1>用户管理</h1><p class="muted">' + n + '</p></div>' +
        '<a class="btn btn-primary" href="#/users/new">新建用户</a></div>';
    };
    if (scenario === 'uam-down') return { crumbs: [['用户管理']], html: head('') + errorState() };
    var q = (r.q.get('q') || '').trim();
    var page = Math.max(1, parseInt(r.q.get('page') || '1', 10) || 1);
    var list = users.filter(function (u) {
      if (!q) return true;
      var n = q.toLowerCase();
      return u.name.toLowerCase().indexOf(n) >= 0 || u.accounts.some(function (a) { return a.loginName.toLowerCase().indexOf(n) >= 0; });
    });
    var pages = Math.max(1, Math.ceil(list.length / PAGE_SIZE));
    if (page > pages) page = pages;
    var slice = list.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
    var toolbar = '<div class="toolbar"><form data-form="search" role="search">' +
      '<input type="search" name="q" aria-label="搜索用户名或账号名" placeholder="搜索用户名或账号名" value="' + esc(q) + '">' +
      '<button class="btn" type="submit">搜索</button></form></div>';
    var body;
    if (!list.length) {
      body = '<div class="card"><div class="state">' + (q
        ? '<strong>没有找到匹配「' + esc(q) + '」的用户</strong>换个关键词试试。<br><a class="btn" href="#/users">清除搜索</a>'
        : '<strong>还没有用户</strong>新建第一个用户开始使用。<br><a class="btn btn-primary" href="#/users/new">新建用户</a>') + '</div></div>';
    } else {
      var rows = slice.map(function (u) {
        return '<tr data-href="/users/' + u.id + '">' +
          '<td class="cell-primary"><a href="#/users/' + u.id + '">' + esc(u.name) + '</a></td>' +
          '<td data-label="状态">' + statusBadge(u.status) + '</td>' +
          '<td data-label="关联账号数">' + u.accounts.length + '</td>' +
          '<td data-label="创建时间">' + fmt(u.created) + '</td>' +
          '<td class="cell-chev">' + I.chev + '</td></tr>';
      }).join('');
      var base = '#/users?' + (q ? 'q=' + encodeURIComponent(q) + '&' : '');
      body = '<div class="card table-card"><table class="tbl"><thead><tr><th>用户名</th><th>状态</th><th>关联账号数</th><th>创建时间</th><th><span class="sr-only">详情</span></th></tr></thead><tbody>' +
        rows + '</tbody></table>' +
        '<nav class="pager" aria-label="分页"><span class="muted small">共 ' + list.length + ' 位用户 · 第 ' + page + ' / ' + pages + ' 页</span><span class="pages">' +
        '<a class="btn" ' + (page > 1 ? 'href="' + base + 'page=' + (page - 1) + '"' : 'aria-disabled="true"') + '>上一页</a>' +
        '<a class="btn" ' + (page < pages ? 'href="' + base + 'page=' + (page + 1) + '"' : 'aria-disabled="true"') + '>下一页</a></span></nav></div>';
    }
    return { crumbs: [['用户管理']], html: head(users.length ? '所有用户及其关联账号' : '') + toolbar + body };
  }

  function newUserView() {
    var err = state.formError ? '<div class="alert alert-err" role="alert"><span class="grow">' + esc(state.formError) + '</span></div>' : '';
    var html = '<div class="page-head"><div class="grow"><h1>新建用户</h1><p class="muted">创建一个用户及其一个「账号名 / 密码」账号。</p></div></div>' +
      '<div class="card form">' + err + '<form data-form="newuser" novalidate>' +
      field('userName', '用户名', { hint: '1–64 个字符，可以与其他用户重名。' }) +
      field('accountName', '账号名', { hint: '3–32 个字母、数字或 _ . -，不区分大小写，创建后不可修改。', autocomplete: 'off' }) +
      field('password', '初始密码', { type: 'password', hint: '至少 10 位，且同时包含字母和数字。', autocomplete: 'new-password' }) +
      '<div class="actions"><button class="btn btn-primary" type="submit">创建用户</button><a class="btn" href="#/users">取消</a></div>' +
      '</form></div>';
    return { crumbs: [['用户管理', '/users'], ['新建用户']], html: html };
  }

  function userDetailView(id) {
    var u = findUser(id);
    if (scenario === 'uam-down') return { crumbs: [['用户管理', '/users'], ['详情']], html: errorState() };
    if (!u) return { crumbs: [['用户管理', '/users'], ['用户不存在']], html: notFoundUser() };
    var isSelf = u.id === OP_USER;
    var lastActive = scenario === 'last-active-user' && u.id === 'u2';
    var reason = '';
    if (u.status === 'ACTIVE' && isSelf) reason = '不能停用当前登录的用户。';
    if (u.status === 'ACTIVE' && lastActive) reason = '不能停用最后一个正常用户。（此状态仅在并发操作下出现，这里用于展示设计。）';
    var rows = u.accounts.map(function (a) {
      return '<tr><td class="cell-primary">' + esc(a.loginName) + '</td>' +
        '<td data-label="账号种类">账号名 / 密码</td>' +
        '<td data-label="状态">' + statusBadge(a.status) + '</td>' +
        '<td data-label="创建时间">' + fmt(a.created) + '</td></tr>';
    }).join('');
    var accounts = u.accounts.length
      ? '<table class="tbl"><thead><tr><th>账号名</th><th>账号种类</th><th>状态</th><th>创建时间</th></tr></thead><tbody>' + rows + '</tbody></table>'
      : '<div class="state"><strong>该用户暂无关联账号</strong>该用户目前无法登录。</div>';
    var action;
    if (u.status === 'ACTIVE') {
      action = '<button class="btn btn-outline-danger" id="btn-disable" data-action="confirm-disable"' + (reason ? ' disabled aria-describedby="disable-reason"' : '') + '>停用用户</button>';
    } else {
      action = '<button class="btn btn-primary" id="btn-enable" data-action="confirm-enable">启用用户</button>';
    }
    var offNote = u.status === 'DISABLED'
      ? '<div class="alert alert-warn" role="status"><span class="grow">该用户已停用：其名下所有账号都无法登录，也没有在线会话。</span></div>' : '';
    var html = '<div class="page-head"><div class="grow"><h1>' + esc(u.name) + '</h1><p class="muted">' + statusBadge(u.status) + (isSelf ? ' <span class="badge badge-info">你</span>' : '') + '</p></div></div>' + offNote +
      '<div class="card"><h2>基本信息</h2><dl class="kv"><dt>用户名</dt><dd>' + esc(u.name) + '</dd><dt>状态</dt><dd>' + statusBadge(u.status) +
      '</dd><dt>创建时间</dt><dd>' + fmt(u.created) + '</dd><dt>用户标识</dt><dd><code>' + esc(u.id) + '</code></dd></dl></div>' +
      '<div class="card table-card"><div style="padding:16px 16px 0"><h2>关联账号（' + u.accounts.length + '）</h2></div>' + accounts + '</div>' +
      '<div class="card"><h2>状态管理</h2><div class="actions">' + action + '</div>' +
      (reason ? '<p class="hint small muted" id="disable-reason" style="margin-top:8px">' + esc(reason) + '</p>' : '') + '</div>';
    return { crumbs: [['用户管理', '/users'], [u.name]], html: html };
  }

  function sessionsView() {
    var head = '<div class="page-head"><div class="grow"><h1>会话管理</h1><p class="muted">当前登录的所有用户（至少有一个有效会话）</p></div></div>';
    if (scenario === 'uam-down') return { crumbs: [['会话管理']], html: head + errorState() };
    var map = {};
    sessions.forEach(function (s) {
      var g = map[s.userId] || (map[s.userId] = { userId: s.userId, count: 0, active: 9999 });
      g.count++; g.active = Math.min(g.active, s.activeMin);
    });
    var groups = Object.keys(map).map(function (k) { return map[k]; }).sort(function (a, b) { return a.active - b.active; });
    var body;
    if (!groups.length) {
      body = '<div class="card"><div class="state"><strong>当前没有在线用户</strong>有用户登录后会显示在这里。</div></div>';
    } else {
      var rows = groups.map(function (g) {
        var u = findUser(g.userId);
        return '<tr data-href="/sessions/' + g.userId + '"><td class="cell-primary"><a href="#/sessions/' + g.userId + '">' + esc(u.name) + '</a>' +
          (g.userId === OP_USER ? ' <span class="badge badge-info">你</span>' : '') + '</td>' +
          '<td data-label="有效会话数">' + g.count + '</td>' +
          '<td data-label="最近活动" title="' + fmt(minsAgo(g.active)) + '">' + rel(g.active) + '</td>' +
          '<td class="cell-chev">' + I.chev + '</td></tr>';
      }).join('');
      body = '<div class="card table-card"><table class="tbl"><thead><tr><th>用户名</th><th>有效会话数</th><th>最近活动</th><th><span class="sr-only">详情</span></th></tr></thead><tbody>' + rows + '</tbody></table>' +
        '<div class="pager"><span class="muted small">共 ' + groups.length + ' 位在线用户</span></div></div>';
    }
    return { crumbs: [['会话管理']], html: head + body };
  }

  function userSessionsView(id) {
    if (scenario === 'uam-down') return { crumbs: [['会话管理', '/sessions'], ['会话']], html: errorState() };
    var u = findUser(id);
    if (!u) return { crumbs: [['会话管理', '/sessions'], ['用户不存在']], html: notFoundUser() };
    var list = userSessions(id);
    var head = '<div class="page-head"><div class="grow"><h1>' + esc(u.name) + ' 的会话</h1><p class="muted">每个会话对应该用户的一个账号。</p></div></div>';
    var body;
    if (!list.length) {
      body = '<div class="card"><div class="state"><strong>该用户当前没有在线会话</strong>可能已退出、到期或被下线。<br><a class="btn" href="#/sessions">返回在线用户</a></div></div>';
    } else {
      body = '<div class="session-list">' + list.map(function (s) {
        var a = accountOf(s.userId, s.accountId);
        var cur = s.ref === OP_SESSION;
        return '<div class="card session"><div class="session-head"><span class="acct">' + esc(a.loginName) + '</span>' +
          '<span class="badge badge-muted">账号名 / 密码</span>' + (cur ? '<span class="badge badge-info">当前会话</span>' : '') + '</div>' +
          '<dl class="kv"><dt>登录时间</dt><dd>' + fmt(minsAgo(s.loginMin)) + '</dd><dt>最近活动</dt><dd>' + fmt(minsAgo(s.activeMin)) + '（' + rel(s.activeMin) + '）</dd>' +
          '<dt>到期时间</dt><dd>' + fmt(expiresAt(s)) + '</dd><dt>会话引用</dt><dd><code>' + esc(fakeRef(s.ref)) + '</code></dd></dl>' +
          '<div><div class="actions"><button class="btn btn-outline-danger" id="btn-term-' + s.ref + '" data-action="confirm-terminate" data-ref="' + s.ref + '"' +
          (cur ? ' disabled aria-describedby="why-' + s.ref + '"' : '') + '>强制下线</button></div>' +
          (cur ? '<p class="hint small muted" id="why-' + s.ref + '" style="margin-top:8px">这是你当前使用的会话，请改用顶栏用户菜单中的「退出登录」。</p>' : '') + '</div></div>';
      }).join('') + '</div>';
    }
    return { crumbs: [['会话管理', '/sessions'], [u.name]], html: head + body };
  }

  function passwordView() {
    var err = state.formError ? '<div class="alert alert-err" role="alert"><span class="grow">' + esc(state.formError) + '</span></div>' : '';
    var html = '<div class="page-head"><div class="grow"><h1>修改密码</h1><p class="muted">修改当前登录账号 <code>zhangsan</code> 的密码。修改后当前会话保持有效。</p></div></div>' +
      '<div class="card form">' + err + '<form data-form="password" novalidate>' +
      field('currentPassword', '当前密码', { type: 'password', autocomplete: 'current-password' }) +
      field('newPassword', '新密码', { type: 'password', hint: '至少 10 位，且同时包含字母和数字，不能与当前密码相同。', autocomplete: 'new-password' }) +
      field('confirmPassword', '确认新密码', { type: 'password', autocomplete: 'new-password' }) +
      '<div class="actions"><button class="btn btn-primary" type="submit">修改密码</button><a class="btn" href="#/users">取消</a></div></form>' +
      '<p class="demo-note">原型演示：当前密码为 <code>' + DEMO_PW + '</code>。</p></div>';
    return { crumbs: [['修改密码']], html: html };
  }

  function notFoundView() {
    return { crumbs: [['页面不存在']], html: '<div class="card"><div class="state"><strong>页面不存在</strong><a class="btn" href="#/users">回到用户管理</a></div></div>' };
  }

  /* ---------- 外壳 ---------- */
  function shell(view, active) {
    var op = findUser(OP_USER) || { name: '张三' };
    var crumbs = view.crumbs.map(function (c, i) {
      var last = i === view.crumbs.length - 1;
      return last ? '<span aria-current="page">' + esc(c[0]) + '</span>' : '<a href="#' + c[1] + '">' + esc(c[0]) + '</a><span aria-hidden="true">/</span>';
    }).join(' ');
    var banner = scenario === 'uam-down'
      ? '<div class="alert alert-warn" role="status"><span class="grow"><strong>服务暂不可用，请稍后重试。</strong> 你的登录状态已保留，服务恢复后无需重新登录。写操作此时会提示「操作未生效」。</span></div>' : '';
    var nav = [['/users', '用户管理', I.users, 'users'], ['/sessions', '会话管理', I.sessions, 'sessions']].map(function (n) {
      return '<li><a href="#' + n[0] + '" title="' + n[1] + '"' + (active === n[3] ? ' aria-current="page"' : '') + '>' + n[2] + '<span class="nav-label">' + n[1] + '</span></a></li>';
    }).join('');
    var menu = state.userMenu
      ? '<div class="user-menu-panel" role="menu"><div class="who"><strong>' + esc(op.name) + '</strong><div class="muted small">账号 zhangsan</div></div>' +
        '<a role="menuitem" href="#/account/password">' + I.key + '修改密码</a>' +
        '<button role="menuitem" data-action="logout">' + I.out + '退出登录</button></div>' : '';
    return '<div class="shell">' +
      '<header class="header"><button class="btn menu-btn" id="menu-btn" data-action="menu" aria-label="打开导航菜单" aria-controls="sidebar" aria-expanded="' + state.drawer + '">' + I.menu + '</button>' +
      '<span class="brand">Admin Center <span class="proto-pill">原型</span></span><span class="spacer"></span>' +
      '<div class="user-menu"><button class="user-btn" id="user-btn" data-action="user-menu" aria-haspopup="menu" aria-expanded="' + state.userMenu + '">' +
      '<span class="avatar" aria-hidden="true">' + esc(op.name.charAt(0)) + '</span><span class="uname">' + esc(op.name) + '</span></button>' + menu + '</div></header>' +
      '<aside class="sidebar' + (state.drawer ? ' open' : '') + '" id="sidebar" aria-label="主导航"><nav><ul class="nav">' + nav + '</ul></nav></aside>' +
      '<div class="backdrop' + (state.drawer ? ' open' : '') + '" data-action="close-drawer"></div>' +
      '<main class="main" id="main"><nav class="crumbs" aria-label="面包屑">' + crumbs + '</nav>' + banner + view.html + '</main></div>';
  }

  function dialogHtml() {
    var d = state.dialog;
    if (!d) return '';
    return '<div class="overlay" data-action="dialog-backdrop"><div class="dialog" role="dialog" aria-modal="true" aria-labelledby="dlg-title" aria-describedby="dlg-body">' +
      '<h2 id="dlg-title">' + esc(d.title) + '</h2><p id="dlg-body">' + esc(d.body) + '</p>' +
      '<div class="actions"><button class="btn" id="dlg-cancel" data-action="dialog-cancel">取消</button>' +
      '<button class="btn ' + (d.danger ? 'btn-danger' : 'btn-primary') + '" data-action="dialog-ok">' + esc(d.ok) + '</button></div></div></div>';
  }
  function toastHtml() {
    var t = state.toast;
    if (!t) return '';
    return '<div class="toast-wrap"><div class="toast ' + (t.kind === 'err' ? 'toast-err' : t.kind === 'ok' ? 'toast-ok' : '') + '" role="' + (t.kind === 'err' ? 'alert' : 'status') + '">' + esc(t.msg) + '</div></div>';
  }

  /* ---------- 渲染 ---------- */
  function render(isNav) {
    var r = parseRoute();
    if (!state.authed && r.path !== '/login') { go('/login'); return; }
    if (state.authed && r.path === '/login') { go('/users'); return; }
    var html;
    if (r.path === '/login') {
      html = loginView();
    } else {
      var segs = r.path.split('/').filter(Boolean);
      var view, active = 'users', title = '用户管理';
      if (segs[0] === 'users' && !segs[1]) { view = usersView(r); }
      else if (segs[0] === 'users' && segs[1] === 'new') { view = newUserView(); title = '新建用户'; }
      else if (segs[0] === 'users') { view = userDetailView(segs[1]); title = '用户详情'; }
      else if (segs[0] === 'sessions' && !segs[1]) { view = sessionsView(); active = 'sessions'; title = '会话管理'; }
      else if (segs[0] === 'sessions') { view = userSessionsView(segs[1]); active = 'sessions'; title = '用户会话'; }
      else if (segs[0] === 'account' && segs[1] === 'password') { view = passwordView(); active = ''; title = '修改密码'; }
      else { view = notFoundView(); active = ''; title = '页面不存在'; }
      document.title = title + ' · Admin Center 原型';
      html = shell(view, active);
    }
    root.innerHTML = html + dialogHtml() + toastHtml();
    if (isNav) window.scrollTo(0, 0);
    if (state.dialog) {
      var c = document.getElementById('dlg-cancel');
      if (c) c.focus();
    } else if (state.restoreFocus) {
      var el = document.getElementById(state.restoreFocus);
      if (el) el.focus();
      state.restoreFocus = null;
    }
  }

  var toastTimer = null;
  function toast(kind, msg) {
    state.toast = { kind: kind, msg: msg };
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { state.toast = null; render(); }, 4500);
  }
  function resetForm() { state.errors = {}; state.values = {}; state.formError = null; }
  function writeBlocked() {
    if (scenario === 'uam-down') { toast('err', '操作未生效：服务暂不可用，请稍后重试。'); render(); return true; }
    return false;
  }

  /* ---------- 动作 ---------- */
  function openDialog(cfg, restoreId) { state.dialog = cfg; state.restoreFocus = restoreId; render(); }
  function closeDialog() { state.dialog = null; render(); }

  var actions = {
    menu: function () {
      state.drawer = !state.drawer; state.restoreFocus = 'menu-btn'; render();
      if (state.drawer) { var a = document.querySelector('#sidebar a'); if (a) a.focus(); }
    },
    'close-drawer': function () { state.drawer = false; state.restoreFocus = 'menu-btn'; render(); },
    'user-menu': function () { state.userMenu = !state.userMenu; state.restoreFocus = 'user-btn'; render(); },
    logout: function () {
      state.userMenu = false; state.authed = false; state.notice = null; resetForm(); state.loginFails = 0; go('/login'); render(true);
    },
    retry: function () { toast('', scenario === 'uam-down' ? '服务仍不可用（原型模拟）。' : '已刷新。'); render(); },
    'dialog-cancel': function () { closeDialog(); },
    'dialog-backdrop': function (e) { if (e.target.classList.contains('overlay')) closeDialog(); },
    'dialog-ok': function () { var d = state.dialog; state.dialog = null; if (d && d.run) d.run(); else render(); },
    'confirm-disable': function (e, btn) {
      var u = findUser(parseRoute().path.split('/')[2]);
      if (!u || btn.disabled) return;
      var n = userSessions(u.id).length;
      openDialog({
        title: '停用用户「' + u.name + '」？', danger: true, ok: '停用',
        body: '停用后，该用户名下的所有账号（' + u.accounts.length + ' 个）都无法登录，其所有在线会话（' + n + ' 个）会立即结束。之后可以重新启用，但已结束的会话不会恢复。',
        run: function () {
          if (writeBlocked()) return;
          u.status = 'DISABLED';
          sessions = sessions.filter(function (s) { return s.userId !== u.id; });
          toast('ok', '用户「' + u.name + '」已停用，其会话已结束。'); state.restoreFocus = 'btn-enable'; render();
        }
      }, 'btn-disable');
    },
    'confirm-enable': function () {
      var u = findUser(parseRoute().path.split('/')[2]);
      if (!u) return;
      openDialog({
        title: '启用用户「' + u.name + '」？', danger: false, ok: '启用',
        body: '启用后，该用户的账号可以重新登录。此前已结束的会话不会恢复。',
        run: function () {
          if (writeBlocked()) return;
          u.status = 'ACTIVE'; toast('ok', '用户「' + u.name + '」已启用。'); state.restoreFocus = 'btn-disable'; render();
        }
      }, 'btn-enable');
    },
    'confirm-terminate': function (e, btn) {
      var ref = btn.getAttribute('data-ref');
      var s = sessions.filter(function (x) { return x.ref === ref; })[0];
      if (!s || btn.disabled) return;
      var a = accountOf(s.userId, s.accountId);
      openDialog({
        title: '强制下线该会话？', danger: true, ok: '强制下线',
        body: '账号「' + a.loginName + '」的这个会话将立即结束；该用户的其他会话不受影响，账号与用户状态也不会改变。对方下一次操作会被带到登录页。',
        run: function () {
          if (writeBlocked()) return;
          sessions = sessions.filter(function (x) { return x.ref !== ref; });
          if (scenario === 'session-gone') toast('', '该会话已结束，已刷新为最新状态。');
          else toast('ok', '会话已强制下线。');
          render();
        }
      }, 'btn-term-' + ref);
    }
  };

  /* ---------- 表单 ---------- */
  var forms = {
    login: function (v) {
      state.values = { accountName: v.accountName || '' }; state.errors = {}; state.formError = null; state.notice = null;
      if (!v.accountName.trim()) state.errors.accountName = '请填写账号名';
      if (!v.password) state.errors.password = '请填写密码';
      if (Object.keys(state.errors).length) { render(); return; }
      if (scenario === 'uam-down') { state.formError = '服务暂不可用，请稍后重试。'; render(); return; }
      if (state.loginFails >= 5) { state.formError = '尝试过于频繁，请 900 秒后再试。'; render(); return; }
      if (v.accountName.trim().toLowerCase() === 'zhangsan' && v.password === DEMO_PW) {
        state.authed = true; state.loginFails = 0; resetForm(); go('/users'); render(true); return;
      }
      state.loginFails++; state.formError = '账号名或密码错误'; render();
    },
    search: function (v) { go('/users' + (v.q.trim() ? '?q=' + encodeURIComponent(v.q.trim()) : '')); },
    newuser: function (v) {
      state.values = { userName: v.userName || '', accountName: v.accountName || '' }; state.errors = {}; state.formError = null;
      var name = v.userName.trim(), an = v.accountName.trim();
      if (!name) state.errors.userName = '请填写用户名'; else if (name.length > 64) state.errors.userName = '用户名最长 64 个字符';
      if (!an) state.errors.accountName = '请填写账号名';
      else if (!/^[A-Za-z0-9_.-]{3,32}$/.test(an)) state.errors.accountName = '账号名为 3–32 个字母、数字或 _ . -';
      else if (accountExists(an)) state.errors.accountName = '账号名已被使用';
      if (!v.password) state.errors.password = '请填写初始密码';
      else if (!strong(v.password)) state.errors.password = '密码至少 10 位，且同时包含字母和数字';
      if (Object.keys(state.errors).length) { render(); return; }
      if (writeBlocked()) return;
      var id = 'u' + (nextId++);
      users.unshift({ id: id, name: name, status: 'ACTIVE', created: NOW, accounts: [acc('a' + id, an, 0)] });
      resetForm(); toast('ok', '用户已创建。'); go('/users/' + id); render(true);
    },
    password: function (v) {
      state.errors = {}; state.formError = null;
      if (!v.currentPassword) state.errors.currentPassword = '请填写当前密码';
      if (!v.newPassword) state.errors.newPassword = '请填写新密码';
      else if (!strong(v.newPassword)) state.errors.newPassword = '新密码至少 10 位，且同时包含字母和数字';
      else if (v.newPassword === v.currentPassword) state.errors.newPassword = '新密码不能与当前密码相同';
      if (!v.confirmPassword) state.errors.confirmPassword = '请再次输入新密码';
      else if (v.confirmPassword !== v.newPassword) state.errors.confirmPassword = '两次输入的新密码不一致';
      if (Object.keys(state.errors).length) { render(); return; }
      if (writeBlocked()) return;
      if (state.pwFails >= 5) { state.formError = '尝试过于频繁，请 900 秒后再试。'; render(); return; }
      if (v.currentPassword !== DEMO_PW) { state.pwFails++; state.errors.currentPassword = '当前密码不正确'; render(); return; }
      state.pwFails = 0; resetForm(); toast('ok', '密码已修改，当前会话保持有效。'); render();
    }
  };

  /* ---------- 事件 ---------- */
  document.addEventListener('click', function (e) {
    var t = e.target;
    var btn = t.closest ? t.closest('[data-action]') : null;
    if (state.userMenu && !(t.closest && t.closest('.user-menu'))) { state.userMenu = false; render(); }
    if (btn && actions[btn.getAttribute('data-action')]) { actions[btn.getAttribute('data-action')](e, btn); return; }
    var row = t.closest ? t.closest('tr[data-href]') : null;
    if (row && !(t.closest('a') || t.closest('button'))) go(row.getAttribute('data-href'));
  });
  document.addEventListener('submit', function (e) {
    var f = e.target.closest ? e.target.closest('form[data-form]') : null;
    if (!f) return;
    e.preventDefault();
    var data = {};
    new FormData(f).forEach(function (val, key) { data[key] = String(val); });
    ['accountName', 'password', 'userName', 'currentPassword', 'newPassword', 'confirmPassword', 'q'].forEach(function (k) { if (data[k] === undefined) data[k] = ''; });
    forms[f.getAttribute('data-form')](data);
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') {
      if (state.dialog) { closeDialog(); return; }
      if (state.userMenu) { state.userMenu = false; state.restoreFocus = 'user-btn'; render(); return; }
      if (state.drawer) { state.drawer = false; state.restoreFocus = 'menu-btn'; render(); return; }
    }
    if (e.key === 'Tab' && state.dialog) {
      var els = Array.prototype.slice.call(document.querySelectorAll('.dialog button'));
      if (!els.length) return;
      var first = els[0], last = els[els.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      else if (els.indexOf(document.activeElement) < 0) { e.preventDefault(); first.focus(); }
    }
  });
  window.addEventListener('hashchange', function () {
    state.drawer = false; state.userMenu = false; state.dialog = null; state.errors = {}; state.formError = null;
    if (parseRoute().path !== '/users/new') state.values = {};
    render(true);
  });

  if (!location.hash) { location.replace('#' + (state.authed ? '/users' : '/login')); }
  render(true);
})();
