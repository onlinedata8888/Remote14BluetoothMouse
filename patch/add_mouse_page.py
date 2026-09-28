#!/usr/bin/env python3
"""Adds the RemoteNOW-style Mouse page to app/assets/remote.html.

Ported from the old RemoteNOW MousePanel gesture engine:
  - zones (cursor area / right vertical scroll bar / bottom horizontal scroll bar)  ratio 1:6
  - swamp threshold 4dp, click < 150ms, drag-hold > 500ms
  - 1 finger  = move   (Google TV has no pointer -> mapped to D-pad steps)
  - tap       = OK
  - hold+move = drag   (OK held down, arrows while moving)
  - 2 fingers = scroll (up/down/left/right)   [pinch = volume]
"""
import sys, re

path = sys.argv[1]
s = open(path, encoding='utf-8').read()

if 'id="mousePage"' in s:
    print("already patched"); sys.exit(0)

# ---------------------------------------------------------------- CSS
CSS = r'''
  /* ================= Mouse page (RemoteNOW-style trackpad) ================= */
  .mouse-btn{
    position:absolute;
    top:10px; left:50%;
    transform:translateX(-50%);
    width:clamp(26px, 9.6cqw, 40px); height:clamp(26px, 9.6cqw, 40px);
    z-index:4;
    background:rgba(20,21,26,0.55);
    backdrop-filter: blur(2px);
  }
  .mouse-btn svg{ width:56%; height:56%; }
  .mouse-page{
    position:absolute; inset:0; z-index:60;
    display:flex; flex-direction:column;
    background:linear-gradient(180deg, var(--screen-bg-1), var(--screen-bg-2));
    padding:calc(env(safe-area-inset-top, 0px) + 14px) 14px 14px;
    gap:10px;
    transform:translateY(102%);
    visibility:hidden;
    transition:transform .32s cubic-bezier(.22,.8,.3,1), visibility 0s .32s;
    touch-action:none;
    user-select:none; -webkit-user-select:none;
  }
  .mouse-page.open{
    transform:translateY(0);
    visibility:visible;
    transition:transform .32s cubic-bezier(.22,.8,.3,1), visibility 0s;
  }
  .mp-top{ flex:none; display:flex; align-items:center; gap:10px; }
  .mp-back{
    width:clamp(34px, 11cqw, 46px); height:clamp(34px, 11cqw, 46px);
  }
  .mp-back svg{ width:52%; height:52%; }
  .mp-title{ flex:1; color:var(--icon); font-size:15px; font-weight:700; letter-spacing:.4px; display:flex; align-items:center; gap:8px; }
  .mp-title .mp-dot{ width:7px; height:7px; border-radius:50%; background:#22c55e; box-shadow:0 0 5px rgba(34,197,94,.7); }
  .mp-title .mp-dot.offline{ background:#ef4444; box-shadow:0 0 5px rgba(239,68,68,.6); }
  .mp-mode{
    flex:none; font-size:11px; letter-spacing:.4px; color:var(--muted-text);
    padding:5px 10px; border-radius:12px; background:var(--btn-bg);
    box-shadow: inset 0 0 0 1px rgba(255,255,255,0.04);
  }
  .mp-mode.drag{ color:var(--slider-fill-1); background:rgba(34,211,184,0.16); box-shadow: inset 0 0 0 1px rgba(34,211,184,.4); }
  .mp-mode.scroll{ color:#f5b942; background:rgba(245,185,66,.14); box-shadow: inset 0 0 0 1px rgba(245,185,66,.4); }
  .mp-mode.zoom{ color:#ef7b7b; background:rgba(239,68,68,.14); box-shadow: inset 0 0 0 1px rgba(239,68,68,.4); }

  .mp-panel{
    position:relative; flex:1 1 auto; min-height:0;
    border-radius:22px;
    background:var(--pad-bg);
    box-shadow: inset 0 0 0 1px var(--pad-border);
    overflow:hidden;
    touch-action:none;
  }
  .mp-panel.active{ background:#31343c; }
  .mp-hint{
    position:absolute; left:0; top:0; right:11%; bottom:11%; display:flex; flex-direction:column;
    align-items:center; justify-content:center; gap:6px; padding:0 8px;
    color:var(--muted-text); font-size:11px; letter-spacing:.3px; text-align:center;
    pointer-events:none; line-height:1.5;
  }
  .mp-hint b{ color:var(--icon-dim); font-weight:600; }
  /* scroll bars (same idea as old MousePanel: right edge = vertical, bottom edge = horizontal) */
  .mp-bar{
    position:absolute; pointer-events:none;
    background:
      repeating-linear-gradient(0deg, rgba(255,255,255,.10) 0 2px, transparent 2px 9px),
      linear-gradient(150deg, #45484f, #1c1b20);
    box-shadow: inset 0 0 0 1px rgba(255,255,255,.10), 0 3px 8px rgba(0,0,0,.4);
    transition: box-shadow .15s ease;
  }
  .mp-bar-v{ top:0; bottom:0; right:0; border-radius:0 22px 22px 0; }
  .mp-bar-h{
    left:0; right:0; bottom:0; border-radius:0 0 22px 22px;
    background:
      repeating-linear-gradient(90deg, rgba(255,255,255,.10) 0 2px, transparent 2px 9px),
      linear-gradient(150deg, #45484f, #1c1b20);
  }
  .mp-bar.hot{ box-shadow: inset 0 0 0 1px rgba(245,185,66,.8), 0 0 0 3px rgba(245,185,66,.18); }
  /* touch feedback dots */
  .mp-dot-touch{
    position:absolute; width:46px; height:46px; margin:-23px 0 0 -23px; border-radius:50%;
    background:radial-gradient(circle, rgba(34,211,184,.45), rgba(34,211,184,0) 70%);
    pointer-events:none; opacity:0; transition:opacity .12s ease;
  }
  .mp-dot-touch.on{ opacity:1; }
  .mp-dot-touch.d2{ background:radial-gradient(circle, rgba(245,185,66,.5), rgba(245,185,66,0) 70%); }
  .mp-ripple{
    position:absolute; width:20px; height:20px; margin:-10px 0 0 -10px; border-radius:50%;
    border:2px solid rgba(34,211,184,.9); pointer-events:none;
    animation:mpRipple .45s ease-out forwards;
  }
  @keyframes mpRipple{ from{ transform:scale(.5); opacity:1; } to{ transform:scale(3.2); opacity:0; } }

  /* bottom row of the mouse page: Back / Home / Play-Pause / Vol - / Vol + */
  .mp-keys{ flex:none; display:grid; grid-template-columns:repeat(5, 1fr); gap:8px; }
  .mp-keys .circle-btn{ width:100%; height:auto; aspect-ratio:1 / 1; border-radius:16px; }
  .mp-keys .circle-btn svg{ width:46%; height:46%; }
  .mp-tip{ flex:none; text-align:center; color:var(--muted-text); font-size:10.5px; letter-spacing:.3px; line-height:1.5; }
'''

# ---------------------------------------------------------------- markup
CURSOR_BTN = '''          <!-- Mouse page button (top-centre of the touchpad) -->
          <button class="circle-btn mouse-btn" id="mouseBtn" aria-label="Open mouse page" title="Mouse">
            <svg viewBox="0 0 24 24" fill="currentColor"><path d="M5.2 2.6c-.6-.5-1.6-.1-1.6.7v15.1c0 .9 1.1 1.3 1.7.6l3.6-4 2.5 5.5c.2.5.8.7 1.3.5l1.9-.9c.5-.2.7-.8.5-1.3l-2.4-5.3 5.3-.5c.9-.1 1.2-1.2.5-1.7L5.2 2.6z"/></svg>
          </button>
'''

MOUSE_PAGE = '''
    <!-- ================= Mouse page (full-screen, slides up over the remote) ================= -->
    <div class="mouse-page" id="mousePage" aria-hidden="true">
      <div class="mp-top">
        <button class="circle-btn mp-back" id="mpBack" aria-label="Back to remote" title="Back">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M15 5l-7 7 7 7"/></svg>
        </button>
        <div class="mp-title"><span class="mp-dot offline" id="mpDot"></span>Mouse</div>
        <div class="mp-mode" id="mpMode">MOVE</div>
      </div>

      <div class="mp-panel" id="mpPanel">
        <div class="mp-hint" id="mpHint">
          <div><b>1 finger</b> &nbsp;move (arrows)</div>
          <div><b>tap</b> &nbsp;OK &nbsp;·&nbsp; <b>hold + move</b> &nbsp;drag</div>
          <div><b>2 fingers</b> &nbsp;scroll &nbsp;·&nbsp; <b>pinch</b> &nbsp;volume</div>
          <div>right edge = up/down scroll</div>
          <div>bottom edge = left/right scroll</div>
        </div>
        <div class="mp-bar mp-bar-v" id="mpBarV"></div>
        <div class="mp-bar mp-bar-h" id="mpBarH"></div>
        <div class="mp-dot-touch" id="mpD1"></div>
        <div class="mp-dot-touch d2" id="mpD2"></div>
      </div>

      <div class="mp-keys">
        <button class="circle-btn" data-mpkey="back" aria-label="Back" title="Back"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round"><path d="M9 14L4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 010 11H11"/></svg></button>
        <button class="circle-btn" data-mpkey="home" aria-label="Home" title="Home"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 11l9-8 9 8"/><path d="M5 10v10h5v-6h4v6h5V10"/></svg></button>
        <button class="circle-btn" data-mpkey="playPause" aria-label="Play / Pause" title="Play / Pause"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 5.4v13.2c0 .5.5.8.9.5l9-6.6c.4-.3.4-.7 0-1l-9-6.6c-.4-.3-.9 0-.9.5z"/><rect x="16" y="5" width="2.6" height="14" rx=".6"/><rect x="20" y="5" width="2.6" height="14" rx=".6"/></svg></button>
        <button class="circle-btn" data-mpkey="volDown" aria-label="Volume down" title="Volume down"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9.5v5h3.5L12 18.5v-13L7.5 9.5H4z" fill="currentColor"/><path d="M16 9.5a4 4 0 010 5"/></svg></button>
        <button class="circle-btn" data-mpkey="volUp" aria-label="Volume up" title="Volume up"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9.5v5h3.5L12 18.5v-13L7.5 9.5H4z" fill="currentColor"/><path d="M16 8a6 6 0 010 8"/><path d="M18.5 5.5a9.5 9.5 0 010 13"/></svg></button>
      </div>
      <div class="mp-tip" id="mpTip">Google TV me asli cursor nahi hota — mouse ki movement D-pad steps me convert hoti hai.</div>
    </div>
'''

# ---------------------------------------------------------------- JS
JS = r'''
  // ================= Mouse page — RemoteNOW MousePanel gesture engine ported =================
  // Original constants (from the old app's MousePanel.smali): swamp = 4dp, click < 150ms, drag-hold > 500ms,
  // panel split horizontally / vertically 1 : 6  => a thin scroll bar on the right and bottom, big cursor area.
  // The old app sent Hisense "REL_xxxx_yyyy" pointer packets. The Google TV remote protocol has NO pointer message,
  // so here cursor movement is converted to D-pad key steps (the same thing a real Google TV remote can do).
  (function(){
    var page = document.getElementById('mousePage');
    var openBtn = document.getElementById('mouseBtn');
    if(!page || !openBtn) return;
    var panel = document.getElementById('mpPanel');
    var backBtn = document.getElementById('mpBack');
    var modeEl = document.getElementById('mpMode');
    var hint = document.getElementById('mpHint');
    var barV = document.getElementById('mpBarV'), barH = document.getElementById('mpBarH');
    var d1 = document.getElementById('mpD1'), d2 = document.getElementById('mpD2');
    var dotEl = document.getElementById('mpDot');
    var phoneEl = page.parentNode;

    // ---- tunables (same numbers as the original where they exist) ----
    var DENSITY = Math.max(1, window.devicePixelRatio || 1);
    var SWAMP = 4;                 // px: movement threshold (orig: 4 * density)
    var CLICK_MS = 150;            // orig clickStartTime
    var DRAG_MS = 500;             // orig dragStartTime
    var BAR_RATIO = 1 / 9;         // orig was 1:6; slightly thinner here so the cursor area stays big
    var MOVE_STEP = 34;            // px of finger travel per D-pad step (cursor area)
    var MOVE_STEP_FAST = 20;       // used when the finger moves fast (like a mouse acceleration curve)
    var SCROLL_STEP = 38;          // px of 2-finger / edge-bar travel per scroll step
    var PINCH_STEP = 22;           // px of pinch distance change per volume step
    var MIN_INTERVAL = 45;         // ms between two key sends (orig throttled with field "l")

    var open = false;
    var lastSend = 0;
    function send(code){
      var now = Date.now();
      if(now - lastSend < MIN_INTERVAL) return false;
      lastSend = now;
      tap(code);
      return true;
    }
    function setMode(txt, cls){
      modeEl.textContent = txt;
      modeEl.className = 'mp-mode' + (cls ? ' ' + cls : '');
    }
    function openPage(){
      open = true;
      page.classList.add('open');
      page.setAttribute('aria-hidden', 'false');
      refreshDot();
      // measure once the slide-in has laid out, so the scroll bars are drawn before the first touch
      requestAnimationFrame(measure); setTimeout(measure, 380);
    }
    function closePage(){
      open = false;
      endAll();
      page.classList.remove('open');
      page.setAttribute('aria-hidden', 'true');
    }
    function refreshDot(){
      var sd = document.getElementById('statusDot');
      var off = !TV || (sd && sd.classList.contains('offline'));
      dotEl.classList.toggle('offline', !!off);
    }
    openBtn.addEventListener('pointerdown', function(e){ e.stopPropagation(); });
    openBtn.addEventListener('click', function(e){ e.stopPropagation(); openPage(); });
    backBtn.addEventListener('click', closePage);

    // Android hardware back / swipe-back: close the mouse page first
    window.addEventListener('keydown', function(e){ if(open && (e.key === 'Escape' || e.key === 'Backspace')) closePage(); });

    // ---- geometry: which zone did the FIRST finger land in (orig isInCursorPanel/isInHorizontallyBar/isInVerticallyBar) ----
    var W = 1, H = 1, X1 = 1, Y1 = 1;
    function measure(){
      var r = panel.getBoundingClientRect();
      W = r.width; H = r.height;
      X1 = W * (1 - BAR_RATIO);   // right of this = vertical scroll bar
      Y1 = H * (1 - BAR_RATIO);   // below this  = horizontal scroll bar
      barV.style.width = (W * BAR_RATIO) + 'px';
      barH.style.height = (H * BAR_RATIO) + 'px';
    }
    function zoneOf(x, y){
      if(y > Y1 && x < X1) return 'h';     // bottom bar
      if(x > X1 && y < Y1) return 'v';     // right bar
      if(x > X1 && y > Y1) return 'none';  // corner
      return 'cursor';
    }

    // ---- state ----
    var pts = {};          // pointerId -> {x,y}
    var order = [];        // pointer ids in touch order
    var mode = 'none';     // none | cursor | v | h | scroll2 | zoom
    var zone = 'none';
    var t0 = 0, moved = false, dragOn = false, holdTimer = null;
    var accX = 0, accY = 0, lastMoveT = 0;
    var scrX = 0, scrY = 0;
    var pinchBase = 0, pinchAcc = 0, pinchDecided = false, lastMid = null;

    function local(e){
      var r = panel.getBoundingClientRect();
      return { x: e.clientX - r.left, y: e.clientY - r.top };
    }
    function showDot(el, p, on){
      if(on){ el.style.left = p.x + 'px'; el.style.top = p.y + 'px'; }
      el.classList.toggle('on', !!on);
    }
    function ripple(p){
      var r = document.createElement('div');
      r.className = 'mp-ripple';
      r.style.left = p.x + 'px'; r.style.top = p.y + 'px';
      panel.appendChild(r);
      setTimeout(function(){ if(r.parentNode) r.parentNode.removeChild(r); }, 500);
    }
    function dist(a, b){ var dx = a.x - b.x, dy = a.y - b.y; return Math.sqrt(dx*dx + dy*dy); }

    function clearHold(){ if(holdTimer){ clearTimeout(holdTimer); holdTimer = null; } }
    function endDrag(){
      if(dragOn){ dragOn = false; if(TV) TV.key(K.ok, 2); }
    }
    function endAll(){
      clearHold(); endDrag();
      pts = {}; order = []; mode = 'none'; moved = false;
      showDot(d1, null, false); showDot(d2, null, false);
      barV.classList.remove('hot'); barH.classList.remove('hot');
      panel.classList.remove('active');
      setMode('MOVE');
    }

    function onDown(e){
      if(!open) return;
      e.preventDefault();
      try{ panel.setPointerCapture(e.pointerId); }catch(err){}
      if(hint){ hint.style.opacity = '.0'; }
      var p = local(e);
      pts[e.pointerId] = p;
      order.push(e.pointerId);
      panel.classList.add('active');
      measure();

      if(order.length === 1){
        zone = zoneOf(p.x, p.y);
        t0 = Date.now(); moved = false; dragOn = false;
        accX = 0; accY = 0; scrX = 0; scrY = 0; lastMoveT = t0;
        if(zone === 'cursor'){
          mode = 'cursor'; setMode('MOVE');
          showDot(d1, p, true);
          // hold still for DRAG_MS => drag mode (orig: isDragTime)
          clearHold();
          holdTimer = setTimeout(function(){
            if(mode === 'cursor' && !moved && order.length === 1){
              dragOn = true; setMode('DRAG', 'drag');
              if(TV) TV.key(K.ok, 1);            // press & hold OK
              if(navigator.vibrate) try{ navigator.vibrate(15); }catch(err){}
            }
          }, DRAG_MS);
        } else if(zone === 'v'){ mode = 'v'; barV.classList.add('hot'); setMode('SCROLL', 'scroll'); }
        else if(zone === 'h'){ mode = 'h'; barH.classList.add('hot'); setMode('SCROLL', 'scroll'); }
        else { mode = 'none'; }
      } else if(order.length === 2){
        // second finger => 2-finger gesture (scroll or pinch); cancel any pending click / drag hold
        clearHold(); endDrag();
        mode = 'scroll2'; moved = true;
        var a = pts[order[0]], b = pts[order[1]];
        pinchBase = dist(a, b); pinchAcc = 0; pinchDecided = false;
        lastMid = { x:(a.x + b.x) / 2, y:(a.y + b.y) / 2 };
        scrX = 0; scrY = 0;
        showDot(d1, a, true); showDot(d2, b, true);
        setMode('SCROLL', 'scroll');
      }
    }

    function onMove(e){
      if(!open || !pts[e.pointerId]) return;
      e.preventDefault();
      var p = local(e), old = pts[e.pointerId];
      var dx = p.x - old.x, dy = p.y - old.y;
      pts[e.pointerId] = p;

      if(mode === 'cursor' && order.length === 1){
        showDot(d1, p, true);
        if(!moved && Math.abs(p.x - 0) >= 0){ /* moved is decided below */ }
        if(!moved){
          var first = pts[order[0]];
          // still inside the click/hold "swamp"? then it is not a move yet (orig isMoved)
          if(Math.abs(dx) <= SWAMP / 2 && Math.abs(dy) <= SWAMP / 2 && Date.now() - t0 < 60){ return; }
        }
        if(Math.abs(dx) > 0.4 || Math.abs(dy) > 0.4){
          if(!moved && !dragOn){ clearHold(); }   // moved before the hold time => plain move mode
          moved = true;
        }
        // velocity based acceleration: fast finger => smaller step => more keys
        var now = Date.now(), dt = Math.max(1, now - lastMoveT); lastMoveT = now;
        var speed = Math.sqrt(dx*dx + dy*dy) / dt;              // px per ms
        var step = speed > 1.1 ? MOVE_STEP_FAST : MOVE_STEP;
        accX += dx; accY += dy;
        var guard = 4;
        while(guard-- > 0){
          var ax = Math.abs(accX), ay = Math.abs(accY);
          if(ax < step && ay < step) break;
          if(ax >= ay){
            if(send(accX > 0 ? K.right : K.left)) accX += accX > 0 ? -step : step; else break;
          } else {
            if(send(accY > 0 ? K.down : K.up)) accY += accY > 0 ? -step : step; else break;
          }
        }
      } else if(mode === 'v' && order.length === 1){
        scrY += dy;
        if(Math.abs(scrY) >= SCROLL_STEP){ if(send(scrY > 0 ? K.down : K.up)) scrY = 0; }
      } else if(mode === 'h' && order.length === 1){
        scrX += dx;
        if(Math.abs(scrX) >= SCROLL_STEP){ if(send(scrX > 0 ? K.right : K.left)) scrX = 0; }
      } else if((mode === 'scroll2' || mode === 'zoom') && order.length >= 2){
        var a = pts[order[0]], b = pts[order[1]];
        if(!a || !b) return;
        showDot(d1, a, true); showDot(d2, b, true);
        var dNow = dist(a, b);
        var mid = { x:(a.x + b.x) / 2, y:(a.y + b.y) / 2 };
        var mdx = mid.x - lastMid.x, mdy = mid.y - lastMid.y;
        lastMid = mid;
        var spread = dNow - pinchBase;
        // decide once: fingers moving APART/TOGETHER a lot => pinch (volume), otherwise => scroll (orig isZoomIn/isZoomOut)
        if(!pinchDecided && (Math.abs(spread) > 26 || Math.abs(mdx) + Math.abs(mdy) > 26)){
          pinchDecided = true;
          mode = Math.abs(spread) > (Math.abs(mdx) + Math.abs(mdy)) * 1.3 ? 'zoom' : 'scroll2';
          if(mode === 'zoom'){ setMode('VOLUME', 'zoom'); pinchAcc = spread; pinchBase = dNow; }   // keep the spread already travelled, don't throw it away
        }
        if(mode === 'zoom'){
          pinchAcc += (dNow - pinchBase); pinchBase = dNow;
          while(Math.abs(pinchAcc) >= PINCH_STEP){
            if(pinchAcc > 0){ if(send(K.volUp)) pinchAcc -= PINCH_STEP; else break; }
            else { if(send(K.volDown)) pinchAcc += PINCH_STEP; else break; }
          }
        } else {
          scrX += mdx; scrY += mdy;
          // natural touch-pad scroll: fingers up => content goes down the list (arrow DOWN), like a phone
          var axs = Math.abs(scrX), ays = Math.abs(scrY);
          if(ays >= SCROLL_STEP && ays >= axs){ if(send(scrY < 0 ? K.down : K.up)) scrY = 0; }
          else if(axs >= SCROLL_STEP && axs > ays){ if(send(scrX < 0 ? K.right : K.left)) scrX = 0; }
        }
      }
    }

    function onUp(e){
      if(!pts[e.pointerId]) return;
      var wasSingle = order.length === 1;
      var p = pts[e.pointerId];
      delete pts[e.pointerId];
      order = order.filter(function(id){ return id !== e.pointerId; });
      try{ panel.releasePointerCapture(e.pointerId); }catch(err){}

      if(wasSingle){
        var dt = Date.now() - t0;
        clearHold();
        if(dragOn){ endDrag(); }
        else if(mode === 'cursor' && !moved && dt < CLICK_MS + 120){   // tap = OK (orig isClickTime)
          tap(K.ok); ripple(p);
        }
        showDot(d1, null, false);
        barV.classList.remove('hot'); barH.classList.remove('hot');
        mode = 'none'; panel.classList.remove('active'); setMode('MOVE');
      } else if(order.length === 1){
        // one finger of a 2-finger gesture lifted: wait for the last one, no accidental click
        showDot(d2, null, false); mode = 'none'; moved = true;
      } else if(order.length === 0){
        showDot(d1, null, false); showDot(d2, null, false);
        mode = 'none'; panel.classList.remove('active'); setMode('MOVE');
      }
    }

    panel.addEventListener('pointerdown', onDown);
    panel.addEventListener('pointermove', onMove);
    panel.addEventListener('pointerup', onUp);
    panel.addEventListener('pointercancel', function(e){ if(pts[e.pointerId]){ delete pts[e.pointerId]; order = order.filter(function(id){ return id !== e.pointerId; }); } if(!order.length) endAll(); });
    panel.addEventListener('contextmenu', function(e){ e.preventDefault(); });
    window.addEventListener('resize', function(){ if(open) measure(); });

    // mouse-wheel on the panel (desktop preview): scroll up / down
    panel.addEventListener('wheel', function(e){
      e.preventDefault();
      send(e.deltaY > 0 ? K.down : K.up);
    }, { passive:false });

    // bottom keys (Back / Home / Play-Pause / Vol - / Vol +): key down on touch, key up on release (holding repeats)
    page.querySelectorAll('[data-mpkey]').forEach(function(b){
      var code = K[b.getAttribute('data-mpkey')];
      if(code != null) press(b, code);
    });
    if(TV){ var tip = document.getElementById('mpTip'); if(tip) tip.style.display = 'block'; }
    else { var tip2 = document.getElementById('mpTip'); if(tip2) tip2.textContent = 'Preview mode: TV connected nahi hai, gestures sirf test ke liye.'; }
  })();
'''

# ---------------------------------------------------------------- apply
# 1) CSS: put right before the closing </style> of the main stylesheet
i = s.index('</style>')
s = s[:i] + CSS + '\n' + s[i:]

# 2) cursor button: inside the touchpad, right after the pad-hint span
anchor = '<span class="pad-hint">touch pad</span>\n'
assert anchor in s, 'pad-hint anchor missing'
s = s.replace(anchor, anchor + '\n' + CURSOR_BTN, 1)

# 3) mouse page markup: make it the LAST child of .phone (depth walk from <div class="stage">, robust against layout edits)
st = s.index('<div class="stage">')
depth = 0; phone_close = None
for mm in re.finditer(r'<div\b[^>]*>|</div>', s[st:]):
    tok = mm.group()
    if tok.startswith('<div'):
        depth += 1
    else:
        depth -= 1
        if depth == 1:            # stage=1 -> phone=2 ; when phone closes depth drops back to 1
            phone_close = st + mm.start(); break
assert phone_close is not None, 'could not find end of .phone'
s = s[:phone_close] + MOUSE_PAGE + '  ' + s[phone_close:]

# 4) JS: right before the closing "})();\n</script>" of the main script
tail = '})();\n</script>'
k = s.rindex(tail)
s = s[:k] + JS + '\n' + s[k:]

open(path, 'w', encoding='utf-8').write(s)
print('OK')
