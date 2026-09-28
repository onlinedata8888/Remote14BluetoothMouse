"""Simulates Android gesture-back (MainActivity.onBackPressed -> TvBridge.handleBack -> window.__tv.handleBack()).
Every back press must close exactly ONE layer; only from the main page does it return false (= app exits)."""
import os, sys
from playwright.sync_api import sync_playwright

HTML = 'file://' + os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'app', 'assets', 'remote.html'))

FAKE = """
window.__calls = [];
window.TVNative = {
  ready(){}, key(c,d){}, keys(c,n){}, text(t){}, launch(l){}, toastMsg(m){}, manualIp(){}, connect(h){},
  reconnect(){}, rescan(){}, pairCode(c){}, cancelPairing(){ window.__calls.push('cancelPairing'); }, voiceToggle(){}, castPick(k){},
  galleryReady(k){ return true; },
  listGalleryAlbums(k){ return JSON.stringify([{id:'a1',name:'Camera',count:3,cover:'1'},{id:'a2',name:'WhatsApp',count:2,cover:'4'}]); },
  listGalleryItems(k,b){ return JSON.stringify([{id:'1',title:'p1'},{id:'2',title:'p2'},{id:'3',title:'p3'}]); },
  castMediaStoreItem(a,b){ window.__calls.push('cast:'+a); }, castPlay(){}, castPause(){}, castStop(){}, castSeek(m){}
};
"""

results = []
def check(name, cond, extra=''):
    results.append(bool(cond)); print(('PASS' if cond else 'FAIL'), '-', name, extra)

def back(page):
    return page.evaluate("window.__tv.handleBack()")

def state(page):
    return page.evaluate("""(function(){
      var c=function(id,cls){var e=document.getElementById(id);return !!e && e.classList.contains(cls);};
      var albumsShown = !!document.querySelector('#castBody .cx-albums');
      var gridShown   = !!document.querySelector('#castBody .cx-grid3, #castBody .cx-grid2');
      return {pane:c('pane','open'), cast:c('castOverlay','show'), albums:albumsShown, grid:gridShown,
              mouse:c('mousePage','open'), kb:c('kbOverlay','open'), dd:c('tvDropdown','open')};
    })()""")

with sync_playwright() as p:
    b = p.chromium.launch()
    ctx = b.new_context(viewport={'width': 400, 'height': 820}, has_touch=True, device_scale_factor=2)
    page = ctx.new_page()
    errs = []
    page.on('pageerror', lambda e: errs.append(str(e)))
    page.add_init_script(FAKE)
    page.goto(HTML); page.wait_for_timeout(600)

    # ---------- exact scenario from the user ----------
    # page1 -> 3-dot (page 2) -> Cast -> Photos -> Album -> photo picked
    page.click('#pageBtn'); page.wait_for_timeout(300)
    check('on 2nd page', state(page)['pane'])
    page.click('[data-key="cast"]'); page.wait_for_timeout(350)
    s = state(page); check('cast opened, albums list visible', s['cast'] and s['albums'], str(s))
    page.click('.cx-album >> nth=1'); page.wait_for_timeout(350)     # open the "Camera" album
    s = state(page); check('inside album (photo grid)', s['cast'] and s['grid'] and not s['albums'], str(s))
    page.click('.cx-thumb >> nth=0'); page.wait_for_timeout(200)     # pick a photo
    check('photo casted', 'cast:1' in page.evaluate('window.__calls'))

    # ---------- now the BACK gestures ----------
    r = back(page); page.wait_for_timeout(350); s = state(page)
    check('back #1: photo grid -> albums list (still in Cast)', r is True and s['cast'] and s['albums'] and not s['grid'], f'ret={r} {s}')
    r = back(page); page.wait_for_timeout(200); s = state(page)
    check('back #2: albums list -> out of Cast, on 2nd page', r is True and not s['cast'] and s['pane'], f'ret={r} {s}')
    r = back(page); page.wait_for_timeout(200); s = state(page)
    check('back #3: 2nd page -> 1st (main) page', r is True and not s['pane'], f'ret={r} {s}')
    r = back(page)
    check('back #4: on main page -> returns false (app may exit)', r is False, f'ret={r}')

    # ---------- other layers ----------
    # Cast opened but user never entered an album: 1 back closes cast
    page.click('#pageBtn'); page.wait_for_timeout(250)
    page.click('[data-key="cast"]'); page.wait_for_timeout(350)
    r = back(page); s = state(page)
    check('cast albums list: back closes cast (stays on 2nd page)', r is True and not s['cast'] and s['pane'], str(s))
    back(page); page.wait_for_timeout(150)

    # Cast: photo tab -> video tab -> album -> back -> back
    page.click('#pageBtn'); page.wait_for_timeout(250)
    page.click('[data-key="cast"]'); page.wait_for_timeout(300)
    page.click('.cx-tab[data-kind="video"]'); page.wait_for_timeout(300)
    page.click('.cx-album >> nth=1'); page.wait_for_timeout(300)
    r = back(page); page.wait_for_timeout(300); s = state(page)
    check('video album -> back -> video albums list', r is True and s['cast'] and s['albums'], str(s))
    back(page); back(page); page.wait_for_timeout(150)

    # Mouse page
    page.click('#mouseBtn'); page.wait_for_timeout(450)
    check('mouse page open', state(page)['mouse'])
    r = back(page); page.wait_for_timeout(200); s = state(page)
    check('mouse page: back closes it, stays in app', r is True and not s['mouse'], str(s))
    check('back afterwards (main page) -> false', back(page) is False)

    # Keyboard overlay
    page.click('#keyboardBtn'); page.wait_for_timeout(250)
    check('keyboard open', state(page)['kb'])
    r = back(page); s = state(page)
    check('keyboard: back closes it', r is True and not s['kb'], str(s))

    # TV dropdown
    page.click('#tvPicker'); page.wait_for_timeout(250)
    check('tv dropdown open', state(page)['dd'])
    r = back(page); s = state(page)
    check('dropdown: back closes it', r is True and not s['dd'], str(s))

    # Stacked layers: 2nd page + keyboard on top -> keyboard first, then page
    page.click('#pageBtn'); page.wait_for_timeout(250)
    page.click('#keyboardBtn'); page.wait_for_timeout(250)
    r1 = back(page); s1 = state(page)
    r2 = back(page); s2 = state(page)
    check('stack: keyboard closes first (page still open)', r1 is True and not s1['kb'] and s1['pane'], str(s1))
    check('stack: then 2nd page closes', r2 is True and not s2['pane'], str(s2))
    check('final back on main page -> false', back(page) is False)

    check('no JS errors', not errs, str(errs))
    b.close()

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
