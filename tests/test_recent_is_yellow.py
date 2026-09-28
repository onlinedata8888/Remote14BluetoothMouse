import os, sys
from playwright.sync_api import sync_playwright
HTML='file://'+os.path.abspath(os.path.join(os.path.dirname(__file__),'..','app','assets','remote.html'))
FAKE="""window.__keys=[];window.TVNative={ready(){},key(c,d){window.__keys.push([c,d])},keys(){},text(){},launch(){},toastMsg(){},manualIp(){},connect(){},reconnect(){},rescan(){},pairCode(){},cancelPairing(){},voiceToggle(){},castPick(){},galleryReady(){return true},listGalleryAlbums(){return '[]'},listGalleryItems(){return '[]'},castMediaStoreItem(){},castPlay(){},castPause(){},castStop(){},castSeek(){}};"""
res=[]
def check(n,c,e=''): res.append(bool(c)); print('PASS' if c else 'FAIL','-',n,e)
with sync_playwright() as p:
    b=p.chromium.launch(); ctx=b.new_context(viewport={'width':400,'height':820},has_touch=True); pg=ctx.new_page()
    errs=[]; pg.on('pageerror',lambda e:errs.append(str(e))); pg.add_init_script(FAKE); pg.goto(HTML); pg.wait_for_timeout(500)
    # Yellow (open 2nd page, tap it)
    pg.click('#pageBtn'); pg.wait_for_timeout(500); pg.evaluate('window.__keys.length=0')
    pg.click('[data-key="yellow"]'); pg.wait_for_timeout(100); y=pg.evaluate('window.__keys.splice(0)')
    pg.click('#pageBtn'); pg.wait_for_timeout(450)          # back to main page (Recent lives here)
    # Recent from main page (2nd page closed)
    pg.click('#recentBtn'); pg.wait_for_timeout(100); r=pg.evaluate('window.__keys.splice(0)')
    check('Yellow sends its key', len(y)>=1, str(y))
    check('Recent sends EXACTLY the same command as Yellow', r==y and len(r)>0, f'yellow={y} recent={r}')
    check('Recent no longer sends old KEYCODE_APP_SWITCH (187)', all(k[0]!=187 for k in r))
    check('2nd page stays closed after pressing Recent', not pg.evaluate("document.getElementById('pane').classList.contains('open')"))
    # Change Yellow's command at runtime -> Recent must follow
    pg.evaluate("""(function(){var b=document.querySelector('#panePage [data-key=\"yellow\"]');
       b.addEventListener('click',function(){window.TVNative.key(999,3);});})()""")
    pg.evaluate('window.__keys.length=0'); pg.click('#recentBtn'); pg.wait_for_timeout(100)
    check('anything added to Yellow also fires from Recent', [999,3] in pg.evaluate('window.__keys'), str(pg.evaluate('window.__keys')))
    check('no JS errors', not errs, str(errs)); b.close()
print(f'\n{sum(res)}/{len(res)} checks passed'); sys.exit(0 if all(res) else 1)
