"""2nd page: no scroll into a blank/black area, content fits; 'Input' button sends KEYCODE_TV_INPUT (178)."""
import os, sys
from playwright.sync_api import sync_playwright
HTML = 'file://' + os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'app', 'assets', 'remote.html'))
FAKE = """
window.__keys=[];
window.TVNative={ready(){},key(c,d){window.__keys.push([c,d])},keys(){},text(){},launch(){},toastMsg(){},manualIp(){},connect(){},reconnect(){},
 rescan(){},pairCode(){},cancelPairing(){},voiceToggle(){},castPick(){},galleryReady(){return true},listGalleryAlbums(){return '[]'},
 listGalleryItems(){return '[]'},castMediaStoreItem(){},castPlay(){},castPause(){},castStop(){},castSeek(){}};
"""
res=[]
def check(n,c,e=''): res.append(bool(c)); print('PASS' if c else 'FAIL','-',n,e)
def scrolls(pg):
    return pg.evaluate("""(function(){var s=document.querySelector('.screen');
      return {body:document.body.scrollTop,html:document.documentElement.scrollTop,screen:s.scrollTop,win:window.scrollY,
              bodySH:document.body.scrollHeight,bodyCH:document.body.clientHeight}})()""")
with sync_playwright() as p:
    b=p.chromium.launch()
    for vw,vh in [(400,820),(412,915),(360,780),(360,740),(390,700)]:
        ctx=b.new_context(viewport={'width':vw,'height':vh},has_touch=True); pg=ctx.new_page()
        errs=[]; pg.on('pageerror',lambda e:errs.append(str(e))); pg.add_init_script(FAKE); pg.goto(HTML); pg.wait_for_timeout(500)
        tag=f'{vw}x{vh}'
        check(f'[{tag}] page has no hidden extra scroll height', scrolls(pg)['bodySH']==scrolls(pg)['bodyCH'], str(scrolls(pg)))
        pg.click('#pageBtn'); pg.wait_for_timeout(550)
        cdp=ctx.new_cdp_session(pg)
        def swipe(x,y1,y2):
            cdp.send('Input.dispatchTouchEvent',{'type':'touchStart','touchPoints':[{'x':x,'y':y1}]})
            for i in range(1,11): cdp.send('Input.dispatchTouchEvent',{'type':'touchMove','touchPoints':[{'x':x,'y':y1+(y2-y1)*i/10}]})
            cdp.send('Input.dispatchTouchEvent',{'type':'touchEnd','touchPoints':[]}); pg.wait_for_timeout(120)
        for _ in range(4): swipe(200,vh-160,120)      # finger drags UP (scroll down) - over the 2nd page
        for _ in range(4): swipe(200,120,vh-160)      # finger drags DOWN
        pg.mouse.move(vw/2,vh/2)
        for _ in range(6): pg.mouse.wheel(0,600)
        sc=scrolls(pg)
        check(f'[{tag}] scrolling 2nd page never moves body/screen (no blank black page)', sc['body']==0 and sc['html']==0 and sc['screen']==0 and sc['win']==0, str(sc))
        fit=pg.evaluate("""(function(){var pp=document.getElementById('panePage'),pr=pp.getBoundingClientRect();
           var last=pp.lastElementChild.getBoundingClientRect(), first=pp.firstElementChild.getBoundingClientRect();
           return {sh:pp.scrollHeight,ch:pp.clientHeight,ov:pp.style.overflowY,firstTop:first.top-pr.top,lastBottom:last.bottom-pr.top}})()""")
        fits = fit['sh']<=fit['ch']+1
        check(f'[{tag}] whole 2nd page fits in view (or tiny-screen fallback stays inside page)', fits or fit['ov']=='auto', str(fit))
        if fits: check(f'[{tag}] all buttons visible (nothing cut off top/bottom)', fit['firstTop']>=0 and fit['lastBottom']<=fit['ch']+1, str(fit))
        check(f'[{tag}] no JS errors', not errs, str(errs))
        ctx.close()

    ctx=b.new_context(viewport={'width':400,'height':820},has_touch=True); pg=ctx.new_page(); pg.add_init_script(FAKE); pg.goto(HTML); pg.wait_for_timeout(400)
    pg.click('#pageBtn'); pg.wait_for_timeout(500)
    check("button is labelled 'Input'", pg.inner_text('.num-tv').strip()=='Input')
    check("no button labelled 'TV' left on 2nd page", pg.locator('#panePage button:text-is("TV")').count()==0)
    pg.evaluate('window.__keys.length=0'); pg.click('.num-tv'); pg.wait_for_timeout(100)
    ks=pg.evaluate('window.__keys'); check('Input sends KEYCODE_TV_INPUT (178) once', ks==[[178,3]], str(ks))
    # main page still scrolls nothing and the pane can be closed again
    pg.click('#pageBtn'); pg.wait_for_timeout(400)
    check('2nd page closes again', not pg.evaluate("document.getElementById('pane').classList.contains('open')"))
    b.close()
print(f'\n{sum(res)}/{len(res)} checks passed'); sys.exit(0 if all(res) else 1)
