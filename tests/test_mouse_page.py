import json, sys
from playwright.sync_api import sync_playwright

HTML = 'file:///home/claude/zipproj/cast7fast-fixed/app/assets/remote.html'

FAKE = """
window.__keys = [];
window.TVNative = {
  ready(){}, key(c,d){ window.__keys.push([c,d]); }, keys(c,n){}, text(t){}, launch(l){},
  toastMsg(m){}, manualIp(){}, connect(h){}, reconnect(){}, rescan(){}, pairCode(c){}, cancelPairing(){},
  voiceToggle(){}, castPick(k){}, galleryReady(k){return true}, listGalleryAlbums(k){return '[]'},
  listGalleryItems(k,b){return '[]'}, castMediaStoreItem(a,b){}, castPlay(){}, castPause(){}, castStop(){}, castSeek(m){}
};
"""

K = dict(up=19, down=20, left=21, right=22, ok=23, volUp=24, volDown=25, back=4, home=3, playPause=85)
NAME = {v: k for k, v in K.items()}

def keys(page):
    raw = page.evaluate("window.__keys.splice(0)")
    return [(NAME.get(c, c), d) for c, d in raw]

def taps_only(ks):
    # tap(code) sends dir 3 (click) ; press() sends 1/2
    return [n for n, d in ks if d == 3]

results = []
def check(name, cond, extra=''):
    results.append((name, bool(cond)))
    print(('PASS' if cond else 'FAIL'), '-', name, extra)

with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={'width': 400, 'height': 820}, has_touch=True, device_scale_factor=2)
    page = ctx.new_page()
    errs = []
    page.on('pageerror', lambda e: errs.append(str(e)))
    page.on('console', lambda m: errs.append(m.text) if m.type == 'error' else None)
    page.add_init_script(FAKE)
    page.goto(HTML)
    page.wait_for_timeout(600)

    # ---- 1. button present + visible inside the touchpad
    check('cursor button exists', page.locator('#mouseBtn').count() == 1)
    bb = page.locator('#mouseBtn').bounding_box()
    tp = page.locator('#touchpad').bounding_box()
    check('button is visible', bb and bb['width'] > 15 and bb['height'] > 15, str(bb))
    inside = bb and tp and bb['x'] >= tp['x'] and bb['y'] >= tp['y'] and bb['x'] + bb['width'] <= tp['x'] + tp['width'] and bb['y'] + bb['height'] <= tp['y'] + tp['height']
    check('button is inside touchpad (top middle)', inside, f"btn={bb} pad={tp}")
    cx = bb['x'] + bb['width'] / 2; pcx = tp['x'] + tp['width'] / 2
    check('button horizontally centred', abs(cx - pcx) < 6, f'{cx:.0f} vs {pcx:.0f}')
    check('button in upper part of pad', bb['y'] - tp['y'] < 30, f"{bb['y']-tp['y']:.0f}px from top")

    # ---- 2. button must NOT trigger the pad's OK-tap
    page.locator('#mouseBtn').click()
    page.wait_for_timeout(500)
    ks = keys(page)
    check('opening page sent NO key to TV', ks == [], str(ks))
    check('mouse page opened', page.evaluate("document.getElementById('mousePage').classList.contains('open')"))
    pb = page.locator('#mousePage').bounding_box()
    vp = page.viewport_size
    check('page covers phone area', pb['height'] > 500, str(pb))
    page.screenshot(path='/home/claude/shot_open.png')

    panel = page.locator('#mpPanel').bounding_box()
    px, py, pw, ph = panel['x'], panel['y'], panel['width'], panel['height']
    print('panel', panel)
    cxm, cym = px + pw * 0.4, py + ph * 0.4

    # ---- helper: touch via CDP so we get real multi-touch
    cdp = ctx.new_cdp_session(page)
    def touch(kind, points):
        cdp.send('Input.dispatchTouchEvent', {'type': kind, 'touchPoints': [
            {'x': x, 'y': y, 'id': i} for i, (x, y) in enumerate(points)]})

    # ---- 3. tap = OK
    touch('touchStart', [(cxm, cym)]); page.wait_for_timeout(60); touch('touchEnd', [])
    page.wait_for_timeout(120)
    ks = keys(page)
    check('tap => OK', taps_only(ks) == ['ok'], str(ks))

    # ---- 4. drag right => right arrows
    touch('touchStart', [(cxm, cym)])
    for i in range(1, 16):
        touch('touchMove', [(cxm + i * 12, cym)]); page.wait_for_timeout(22)
    touch('touchEnd', []); page.wait_for_timeout(150)
    ks = taps_only(keys(page))
    check('drag right => RIGHT keys only', len(ks) >= 2 and set(ks) == {'right'}, str(ks))

    # ---- 5. drag down => down arrows, drag up => up
    touch('touchStart', [(cxm, cym)])
    for i in range(1, 16):
        touch('touchMove', [(cxm, cym + i * 12)]); page.wait_for_timeout(22)
    touch('touchEnd', []); page.wait_for_timeout(150)
    ks = taps_only(keys(page))
    check('drag down => DOWN keys', len(ks) >= 2 and set(ks) == {'down'}, str(ks))
    touch('touchStart', [(cxm, cym + 200)])
    for i in range(1, 16):
        touch('touchMove', [(cxm, cym + 200 - i * 12)]); page.wait_for_timeout(22)
    touch('touchEnd', []); page.wait_for_timeout(150)
    ks = taps_only(keys(page))
    check('drag up => UP keys', len(ks) >= 2 and set(ks) == {'up'}, str(ks))

    # ---- 6. drag left
    touch('touchStart', [(cxm + 120, cym)])
    for i in range(1, 16):
        touch('touchMove', [(cxm + 120 - i * 12, cym)]); page.wait_for_timeout(22)
    touch('touchEnd', []); page.wait_for_timeout(150)
    ks = taps_only(keys(page))
    check('drag left => LEFT keys', len(ks) >= 2 and set(ks) == {'left'}, str(ks))

    # ---- 7. hold still => drag mode = OK down/up (dir 1 then 2)
    touch('touchStart', [(cxm, cym)]); page.wait_for_timeout(650)
    mode_txt = page.locator('#mpMode').inner_text()
    for i in range(1, 10):
        touch('touchMove', [(cxm + i * 12, cym)]); page.wait_for_timeout(22)
    touch('touchEnd', []); page.wait_for_timeout(150)
    raw = keys(page)
    check('hold => DRAG mode label', mode_txt == 'DRAG', mode_txt)
    check('hold: OK pressed DOWN then released UP', raw[0] == ('ok', 1) and raw[-1] == ('ok', 2), str(raw))
    check('hold+move sent arrows between', any(n == 'right' for n, d in raw), str(raw))

    # ---- 8. two-finger scroll (both fingers move up together) => DOWN (natural scroll)
    a = (cxm - 30, cym + 120); c2 = (cxm + 30, cym + 120)
    touch('touchStart', [a, c2])
    for i in range(1, 14):
        touch('touchMove', [(a[0], a[1] - i * 14), (c2[0], c2[1] - i * 14)]); page.wait_for_timeout(24)
    mode_txt = page.locator('#mpMode').inner_text()
    touch('touchEnd', []); page.wait_for_timeout(150)
    ks = taps_only(keys(page))
    check('2-finger scroll shows SCROLL label', mode_txt == 'SCROLL', mode_txt)
    check('2-finger swipe up => DOWN keys, no OK click', len(ks) >= 2 and set(ks) == {'down'}, str(ks))

    # opposite direction
    touch('touchStart', [a, c2])
    for i in range(1, 14):
        touch('touchMove', [(a[0], a[1] + i * 14), (c2[0], c2[1] + i * 14)]); page.wait_for_timeout(24)
    touch('touchEnd', []); page.wait_for_timeout(150)
    ks = taps_only(keys(page))
    check('2-finger swipe down => UP keys', len(ks) >= 2 and set(ks) == {'up'}, str(ks))

    # horizontal 2-finger
    touch('touchStart', [a, c2])
    for i in range(1, 14):
        touch('touchMove', [(a[0] + i * 14, a[1]), (c2[0] + i * 14, c2[1])]); page.wait_for_timeout(24)
    touch('touchEnd', []); page.wait_for_timeout(150)
    ks = taps_only(keys(page))
    check('2-finger swipe right => LEFT keys (natural)', len(ks) >= 2 and set(ks) == {'left'}, str(ks))

    # ---- 9. pinch out => volume up ; pinch in => volume down
    m = (cxm, cym + 60)
    touch('touchStart', [(m[0] - 25, m[1]), (m[0] + 25, m[1])])
    for i in range(1, 12):
        touch('touchMove', [(m[0] - 25 - i * 9, m[1]), (m[0] + 25 + i * 9, m[1])]); page.wait_for_timeout(24)
    mode_txt = page.locator('#mpMode').inner_text()
    touch('touchEnd', []); page.wait_for_timeout(150)
    ks = taps_only(keys(page))
    check('pinch out => VOLUME label', mode_txt == 'VOLUME', mode_txt)
    check('pinch out => VOL UP', len(ks) >= 2 and set(ks) == {'volUp'}, str(ks))
    touch('touchStart', [(m[0] - 120, m[1]), (m[0] + 120, m[1])])
    for i in range(1, 12):
        touch('touchMove', [(m[0] - 120 + i * 9, m[1]), (m[0] + 120 - i * 9, m[1])]); page.wait_for_timeout(24)
    touch('touchEnd', []); page.wait_for_timeout(150)
    ks = taps_only(keys(page))
    check('pinch in => VOL DOWN', len(ks) >= 2 and set(ks) == {'volDown'}, str(ks))

    # ---- 10. right edge bar = vertical scroll (1 finger), bottom edge = horizontal
    ex = px + pw * 0.95
    touch('touchStart', [(ex, py + 60)])
    for i in range(1, 14):
        touch('touchMove', [(ex, py + 60 + i * 14)]); page.wait_for_timeout(24)
    touch('touchEnd', []); page.wait_for_timeout(150)
    ks = taps_only(keys(page))
    check('right bar drag down => DOWN keys', len(ks) >= 2 and set(ks) == {'down'}, str(ks))
    by = py + ph * 0.95
    touch('touchStart', [(px + 40, by)])
    for i in range(1, 14):
        touch('touchMove', [(px + 40 + i * 14, by)]); page.wait_for_timeout(24)
    touch('touchEnd', []); page.wait_for_timeout(150)
    ks = taps_only(keys(page))
    check('bottom bar drag right => RIGHT keys', len(ks) >= 2 and set(ks) == {'right'}, str(ks))
    # tap on the bar must not click OK
    touch('touchStart', [(ex, py + 100)]); page.wait_for_timeout(50); touch('touchEnd', []); page.wait_for_timeout(120)
    check('tap on scroll bar => no OK', keys(page) == [])

    # ---- 11. bottom key row
    page.locator('[data-mpkey="home"]').dispatch_event('pointerdown', {'pointerId': 5})
    page.locator('[data-mpkey="home"]').dispatch_event('pointerup', {'pointerId': 5})
    page.wait_for_timeout(100)
    raw = keys(page)
    check('Home key => (home,1)+(home,2)', ('home', 1) in raw and ('home', 2) in raw, str(raw))

    page.screenshot(path='/home/claude/shot_gesture.png')

    # ---- 12. close returns to remote and touchpad still works as before
    page.locator('#mpBack').click(); page.wait_for_timeout(500)
    check('back closes page', not page.evaluate("document.getElementById('mousePage').classList.contains('open')"))
    keys(page)
    tp = page.locator('#touchpad').bounding_box()
    touch('touchStart', [(tp['x'] + tp['width'] * 0.5, tp['y'] + tp['height'] * 0.6)]); page.wait_for_timeout(50); touch('touchEnd', []); page.wait_for_timeout(150)
    ks = taps_only(keys(page))
    check('original touchpad still sends OK on tap', ks == ['ok'], str(ks))
    page.screenshot(path='/home/claude/shot_closed.png')

    check('no JS errors', not errs, str(errs[:3]))
    b.close()

bad = [n for n, ok in results if not ok]
print('\n%d/%d passed' % (len(results) - len(bad), len(results)))
if bad: print('FAILED:', bad); sys.exit(1)
