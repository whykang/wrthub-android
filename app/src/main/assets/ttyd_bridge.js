// 注入 ttyd 页面的桥,与 iOS TerminalViewController.bridgeScript 同一份逻辑。
//
// 登录提示的原文是真机上抓的:`ImmortalWrt login: ` → `Password: `,
// 失败时是 `Login incorrect`。自动登录只试**一次**,失败就停手交给用户,
// 不会反复用错误密码撞 —— 那样可能触发 login 的失败延迟甚至封锁。
(function () {
  // iOS 走 WKScriptMessageHandler,安卓走 addJavascriptInterface 注入的 WrtHubAndroid
  function post(m) {
    try {
      if (window.WrtHubAndroid) window.WrtHubAndroid.post(m);
      else window.webkit.messageHandlers.wrthub.postMessage(m);
    } catch (e) {}
  }

  function currentLine(t) {
    var b = t.buffer.active, l = b.getLine(b.baseY + b.cursorY);
    return l ? l.translateToString(true) : '';
  }
  function recentText(t, n) {
    var b = t.buffer.active, end = b.baseY + b.cursorY, out = [];
    for (var i = Math.max(0, end - n); i <= end; i++) {
      var l = b.getLine(i); if (l) out.push(l.translateToString(true));
    }
    return out.join('\n');
  }

  function install() {
    var t = window.term;
    if (!t || !t._core || !t._core.coreService) return false;
    if (window.__whInstalled) return true;
    var cs = t._core.coreService;
    var orig = cs.triggerDataEvent.bind(cs);

    cs.triggerDataEvent = function (d, u) {
      if (window.__whCtrl && typeof d === 'string' && d.length === 1) {
        var c = d.toUpperCase().charCodeAt(0);
        if (c >= 64 && c <= 95) d = String.fromCharCode(c - 64);
        else if (d === '?') d = '\x7f';
        window.__whCtrl = false;
        post('ctrlOff');
      }
      return orig(d, u);
    };
    window.__whSend = function (d) { cs.triggerDataEvent(d, true); try { t.focus(); } catch (e) {} };
    window.__whRaw = function (d) { orig(d, true); try { t.focus(); } catch (e) {} };
    window.__whFit = function () { try { t.fit && t.fit(); } catch (e) {} };
    // AI 执行用:发送但不抢焦点(AI 面板盖在上面,不能把键盘抢到终端)
    window.__whRun = function (d) { orig(d, true); };
    // AI 执行用:在整个缓冲区里找 __WHB_<tag> / __WHE_<tag>_<code> 两行标记,取中间的输出。
    // 折行(isWrapped)先拼回一整行,不然长输出会被拆碎、标记也可能认不出。
    window.__whCapture = function (tag) {
      var b = t.buffer.active, lines = [];
      for (var i = 0; i < b.length; i++) {
        var l = b.getLine(i); if (!l) continue;
        var s = l.translateToString(true);
        if (l.isWrapped && lines.length) lines[lines.length - 1] += s; else lines.push(s);
      }
      var begin = '__WHB_' + tag, endRe = new RegExp('^__WHE_' + tag + '_(\\d+)\\s*$');
      var bi = -1, ei = -1, code = null;
      for (var j = lines.length - 1; j >= 0; j--) {
        if (lines[j].replace(/\s+$/, '') === begin) { bi = j; break; }
      }
      for (var k = bi + 1; k < lines.length; k++) {
        var m = endRe.exec(lines[k]); if (m) { ei = k; code = parseInt(m[1], 10); break; }
      }
      // 开始标记已经滚出回滚缓冲区:只能取结束标记前的 200 行
      if (bi < 0 && ei < 0) return JSON.stringify({ done: false, out: '' });
      var from = bi >= 0 ? bi + 1 : Math.max(0, ei - 200);
      var out = lines.slice(from, ei >= 0 ? ei : lines.length)
        .filter(function (s) { return s.indexOf('__WH%s') < 0; });
      if (ei < 0) {
        while (out.length && /^\s*$/.test(out[out.length - 1])) out.pop();
        // 没等到结束标记(被打断):末行是提示符就去掉
        if (out.length && /[#$]\s*$/.test(out[out.length - 1])) out.pop();
      }
      return JSON.stringify({ done: ei >= 0, code: code,
                              out: out.join('\n').replace(/^\s*\n/, '').replace(/\s+$/, '') });
    };
    // 给 AI 用:终端最近 n 行(去掉末尾空行)
    window.__whScreen = function (n) {
      return recentText(t, n).replace(/\s+$/, '');
    };
    // 光标所在行以 # 或 $ 结尾 = 停在 shell 提示符,可以安全地敲命令
    window.__whAtShell = function () {
      return /[#$]$/.test(currentLine(t).replace(/\s+$/, ''));
    };

    window.__whFont = function (size) { t.options.fontSize = size; window.__whFit(); };
    window.__whStyle = function (bg, size) {
      var theme = Object.assign({}, t.options.theme || {}, {
        background: bg, foreground: '#E6E6E6', cursor: '#7AA2F7',
        selectionBackground: 'rgba(122,162,247,0.35)'
      });
      t.options.theme = theme;
      t.options.fontSize = size;
      t.options.fontFamily = 'Menlo, SFMono-Regular, monospace';
      t.options.lineHeight = 1.15;
      var css = document.createElement('style');
      css.textContent = 'html,body{margin:0;height:100%;background:' + bg + ';}' +
        '#terminal-container{box-sizing:border-box;height:100%;padding:6px 8px;}' +
        '.xterm-viewport{background:' + bg + ' !important;}';
      document.head.appendChild(css);
      setTimeout(window.__whFit, 50);
    };

    // 自动登录状态机:0 等登录提示 → 1 等密码提示 → 2 等结果
    window.__whAutoLogin = function (user, pass) {
      if (window.__whLoginStarted) return;
      window.__whLoginStarted = true;
      var state = 0, started = Date.now();
      var timer = setInterval(function () {
        var line = currentLine(t).replace(/\s+$/, '');
        if (state === 0) {
          if (/login:$/i.test(line)) { orig(user + '\r', true); state = 1; post('loggingIn'); }
          // 页面起来 8 秒还没看到登录提示:命令不是 /bin/login(比如直接开 shell),不管了
          else if (Date.now() - started > 8000) { clearInterval(timer); post('noLogin'); }
        } else if (state === 1) {
          if (/password:$/i.test(line)) { orig(pass + '\r', true); state = 2; }
        } else {
          var recent = recentText(t, 4);
          if (/Login incorrect/i.test(recent)) { clearInterval(timer); post('loginFailed'); }
          // /bin/login 自带 60 秒超时(真机实测),页面挂着没人动就会断
          else if (/Login timed out/i.test(recent)) { clearInterval(timer); post('loginTimeout'); }
          else if (/[#$]$/.test(line)) { clearInterval(timer); post('loggedIn:' + line); }
        }
        if (Date.now() - started > 20000) { clearInterval(timer); if (state !== 0) post('loginTimeout'); }
      }, 200);
    };

    window.__whInstalled = true;
    post('ready');
    return true;
  }
  if (!install()) {
    var timer = setInterval(function () { if (install()) clearInterval(timer); }, 200);
    setTimeout(function () { clearInterval(timer); }, 15000);
  }
})();
