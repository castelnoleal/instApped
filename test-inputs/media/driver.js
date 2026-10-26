// Emulator test driver for media-player-test.yml: appended to the Pulse player page.
// Logs MEDIA_TEST:<step> lines, which reach logcat through the app's console bridge.
(function () {
  var log = function (m) { console.log('MEDIA_TEST:' + m); };
  var P = function () { return window.__pulse; };
  function wait(f, ms) {
    return new Promise(function (res, rej) {
      var t0 = Date.now();
      var iv = setInterval(function () {
        var v; try { v = f(); } catch (e) { v = false; }
        if (v) { clearInterval(iv); res(v); } else if (Date.now() - t0 > (ms || 30000)) { clearInterval(iv); rej(new Error('timeout')); }
      }, 250);
    });
  }
  var step = 'START';
  async function run() {
    try {
      step = 'BOOT'; await wait(function () { return P(); });
      log('BRIDGE:' + P().NATIVE);
      if (!P().NATIVE) throw new Error('window.CastelMedia missing');
      // Log notification/headset events as they arrive.
      var orig = window.CastelMediaEvents;
      window.CastelMediaEvents = function (a, v) { log('EVENT:' + a); return orig(a, v); };
      step = 'SCAN'; await wait(function () { return Array.from(P().S.tracks.values()).filter(function (t) { return t.mid != null; }).length >= 3; }, 90000);
      var dev = Array.from(P().S.tracks.values()).filter(function (t) { return t.mid != null; });
      log('SCAN_OK:' + dev.length + ':' + dev.map(function (t) { return t.title; }).join('|'));
      var ids = dev.sort(function (a, b) { return a.title.localeCompare(b.title); }).map(function (t) { return t.id; });
      step = 'PLAY'; P().playList(ids, 0, 'Test');
      await wait(function () { return !P().audio.paused && P().audio.currentTime > 1; }, 30000);
      log('PLAY_OK:' + P().S.tracks.get(P().S.cur).title + ':ctx=' + (P().A.ctx ? P().A.ctx.state : 'none'));
      step = 'SEEK'; P().audio.currentTime = 12;
      await wait(function () { return P().audio.currentTime >= 12 && !P().audio.paused; }, 15000);
      log('SEEK_OK:' + P().audio.currentTime.toFixed(1));
      step = 'NEXT'; var c0 = P().S.cur; P().next();
      await wait(function () { return P().S.cur !== c0 && !P().audio.paused && P().audio.currentTime > 0.5; }, 20000);
      log('NEXT_OK:' + P().S.tracks.get(P().S.cur).title);
      step = 'AUTONEXT'; var c1 = P().S.cur; P().audio.currentTime = Math.max(0, P().audio.duration - 1.5);
      await wait(function () { return P().S.cur !== c1 && !P().audio.paused; }, 20000);
      log('AUTONEXT_OK:' + P().S.tracks.get(P().S.cur).title);
      step = 'BACKGROUND';
      P().S.set.repeat = 'all';
      log('READY_BG');
      setInterval(function () {
        var t = P().S.tracks.get(P().S.cur);
        log('POS:' + (t ? t.title : '-') + ':' + P().audio.currentTime.toFixed(1) + ':' + (P().audio.paused ? 'paused' : 'playing') + ':' + document.visibilityState);
      }, 1000);
    } catch (e) {
      log('FAIL:' + step + ':' + (e && e.message));
    }
  }
  if (document.readyState === 'complete') run(); else addEventListener('load', run);
})();
