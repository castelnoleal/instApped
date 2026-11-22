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
      log('PLAY_OK:' + P().S.tracks.get(P().S.cur).title + ':native=' + P().audio.native);
      if (!P().ENGINE || !P().audio.native) throw new Error('device song is not playing in Android\'s player');
      var es = function () { return JSON.parse(window.CastelMedia.engineState()); };
      var p0 = es().pos; await new Promise(function (r) { setTimeout(r, 2000); });
      if (!(es().playing && es().pos > p0 + 1200)) throw new Error('Android player not advancing ' + JSON.stringify(es()));
      log('ENGINE_OK:' + p0 + '->' + es().pos);
      step = 'EFFECTS'; P().S.set.boost = 200; P().S.set.eqOn = true; P().S.set.eq = [6,5,3,1,0,0,0,1,2,3]; P().A.apply();
      log('FX:' + JSON.stringify(P().A.caps));
      await new Promise(function (r) { setTimeout(r, 1500); });
      if (!es().playing) throw new Error('playback stopped after turning on effects');
      step = 'SEEK'; P().audio.currentTime = 12;
      await wait(function () { var e = es(); return e.pos >= 12000 && e.playing; }, 15000);
      log('SEEK_OK:' + (es().pos / 1000).toFixed(1));
      step = 'NEXT'; var c0 = P().S.cur; P().next();
      await wait(function () { return P().S.cur !== c0 && !P().audio.paused && P().audio.currentTime > 0.5; }, 20000);
      log('NEXT_OK:' + P().S.tracks.get(P().S.cur).title);
      step = 'AUTONEXT'; var c1 = P().S.cur, tok = P().audio.n.token; P().audio.currentTime = Math.max(0, P().audio.duration - 1.5);
      await wait(function () { return P().S.cur !== c1 && es().playing; }, 20000);
      // Gapless: Android started the next song itself (the page did not reload it).
      log('AUTONEXT_OK:' + P().S.tracks.get(P().S.cur).title + ':gapless=' + (P().audio.n.token === tok));
      if (P().audio.n.token !== tok) throw new Error('next song was reloaded by the page instead of the gapless hand-off');
      step = 'BACKGROUND';
      P().S.set.repeat = 'all';
      log('READY_BG');
      setInterval(function () {
        var t = P().S.tracks.get(P().S.cur);
        var e = {}; try { e = es(); } catch (x) { }
        log('POS:' + (t ? t.title : '-') + ':' + ((e.pos || 0) / 1000).toFixed(1) + ':' + (e.playing ? 'playing' : 'paused') + ':' + document.visibilityState);
      }, 1000);
    } catch (e) {
      log('FAIL:' + step + ':' + (e && e.message));
      try {
        var a = P().audio, t = P().S.tracks.get(P().S.cur) || {};
        try { log('ENGINE_STATE:' + window.CastelMedia.engineState()); } catch (x) { }
        log('DIAG:' + JSON.stringify({ src: (a.currentSrc || '').slice(-60), paused: a.paused, ct: a.currentTime, dur: a.duration, rs: a.readyState, ns: a.networkState, err: a.error && (a.error.code + ' ' + a.error.message), ctx: P().A.ctx ? P().A.ctx.state : (P().A.failed ? 'failed' : 'none'), wantUrl: (t.url || '').slice(-40), mime: t.mime }));
        if (t.url) fetch(t.url, { headers: { Range: 'bytes=0-99' } }).then(function (r) { log('FETCH:' + r.status + ' ' + r.headers.get('content-type') + ' ' + r.headers.get('content-range')); }, function (e2) { log('FETCH_ERR:' + e2); });
      } catch (e3) { log('DIAG_ERR:' + e3); }
    }
  }
  if (document.readyState === 'complete') run(); else addEventListener('load', run);
})();
