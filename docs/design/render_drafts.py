"""Render the key-screen drafts (ADR 0019) to key-screens-draft-2.png.

Needs Playwright with Chromium and Figtree next to this file:
  curl -L -o Figtree.ttf "https://github.com/google/fonts/raw/main/ofl/figtree/Figtree%5Bwght%5D.ttf"
Mockups only — structure and hierarchy, not production layout.
"""
from pathlib import Path
HERE = Path(__file__).resolve().parent
from playwright.sync_api import sync_playwright
L = dict(bg='#f6f5f2', surface='#ffffff', variant='#ecebe7', on='#0a0a0a', onvar='#5e5d59', outline='#d6d4ce', primary='#0a0a0a', onprimary='#f6f5f2', value='#f7931a', onvalue='#0a0a0a', valuetext='#a65500')
D = dict(bg='#0a0a0a', surface='#161615', variant='#232220', on='#f6f5f2', onvar='#a3a19b', outline='#3a3935', primary='#f6f5f2', onprimary='#0a0a0a', value='#f7931a', onvalue='#0a0a0a', valuetext='#f7931a')
def hop(c, dot=None, w=20):
    return f'<svg width="{w}" viewBox="0 0 100 100" style="vertical-align:middle"><path d="M22 60C22 18 78 18 78 52" fill="none" stroke="{c}" stroke-width="11" stroke-linecap="round"/><circle cx="78" cy="72" r="14" fill="{dot or c}"/></svg>'
COVER = 'background:linear-gradient(135deg,#1f3550 0%,#2b4a6f 45%,#8a5a2b 100%)'
def frame(t, inner, title):
    return f'''<div class="wrap"><div class="cap">{title}</div><div class="phone" style="background:{t['bg']};color:{t['on']}">
      <div class="status" style="color:{t['on']}">9:41<span>▮▮▮ ◔</span></div>{inner}</div></div>'''
def css_vars(t): return ';'.join(f'--{k}:{v}' for k,v in t.items())

def now_playing(t):
    return frame(t, f'''
    <div class="bar"><span class="ic">⌄</span><div style="text-align:center"><div class="sm" style="color:{t['onvar']}">Nodesignal</div></div><span class="ic">⋯</span></div>
    <div class="cover big" style="{COVER}"><div class="chapnav"><span class="cb">‹</span><span class="ct"><b>Nostr-Zaps im Podcast</b><br><span style="opacity:.75">Kapitel 4 von 9 · noch 6:12</span></span><span class="cb">›</span></div></div>
    <div class="ttl">Folge 296 – Value for Value im Alltag</div>
    <div class="sub" style="color:{t['onvar']}">Nodesignal · Karsten, Cutting</div>
    <div class="prog"><div class="track" style="background:{t['variant']}"><div class="fill" style="background:{t['on']};width:38%"></div>
      {''.join(f'<i style="left:{x}%;background:{t["bg"]}"></i>' for x in (12,27,38,61,80))}<div class="knob" style="background:{t['on']};left:38%"></div></div>
      <div class="times" style="color:{t['onvar']}"><span>27:14</span><span>−44:51</span></div></div>
    <div class="ctl"><span class="sm">1,2×</span><span class="ic2">↺<sub>15</sub></span>
      <span class="play" style="background:{t['primary']};color:{t['onprimary']}">❚❚</span><span class="ic2">↻<sub>30</sub></span><span class="sm">☾</span></div>
    <div class="value" style="background:{t['surface']};border:1px solid {t['outline']}">
      <button class="pill vb" style="background:{t['value']};color:{t['onvalue']}">{hop(t['onvalue'])} Boost</button>
      <div class="stream"><div class="sm b">Streaming · 10 sats/min</div><div class="sm" style="color:{t['valuetext']}">heute 120 sats</div></div>
      <div class="switch on" style="background:{t['value']}"><div style="background:{t['onvalue']}"></div></div></div>
    <div class="sheets">{''.join(f'<span style="background:{t["variant"]}">{x}</span>' for x in ('Kapitel','Transkript','Personen','Community'))}</div>
    ''', 'Wiedergabe – dunkel' if t is D else 'Wiedergabe – hell')

def show_page(t):
    eps = [('Folge 296 – Value for Value im Alltag','Gestern · 1 Std. 12 Min.', '2.100'),('Folge 295 – Cashu für Podcaster','vor 8 Tagen · 58 Min.','4.320'),('Folge 294 – Release Boosting erklärt','vor 15 Tagen · 1 Std. 4 Min.','1.050')]
    rows = ''.join(f'''<div class="ep" style="border-bottom:1px solid {t['outline']}"><div class="cover tiny" style="{COVER}"></div><div style="flex:1">
       <div class="b">{a}</div><div class="sm" style="color:{t['onvar']}">{b}</div><div class="sm" style="color:{t['valuetext']};margin-top:2px">{hop(t['valuetext'], w=13)} {c} sats</div></div>
       <span class="mini" style="border:1.5px solid {t['outline']}">▶</span></div>''' for a,b,c in eps)
    return frame(t, f'''
    <div class="bar"><span class="ic">‹</span><span></span><span class="ic">⋯</span></div>
    <div class="head"><div class="cover mid" style="{COVER}"></div><div><div class="ttl" style="margin:0">Nodesignal</div>
      <div class="sm" style="color:{t['onvar']}">Deine Bitcoin-Frequenz · wöchentlich</div>
      <button class="pill small" style="background:{t['primary']};color:{t['onprimary']};margin-top:10px">✓ Abonniert</button></div></div>
    <div class="sc" style="background:{t['surface']};border:1px solid {t['outline']}">
      <div class="sm b" style="color:{t['valuetext']}">RELEASE BOOSTING · via Sovereigncast</div>
      <div class="b" style="margin:4px 0 8px">Folge 297 kommt früher heraus</div>
      <div class="track thick" style="background:{t['variant']}"><div class="fill" style="background:{t['value']};width:62%"></div></div>
      <div class="row2"><span class="sm" style="color:{t['onvar']}">noch 3 Tg. 4 Std. · 12.400 sats geboostet</span>
      <button class="pill small" style="background:{t['value']};color:{t['onvalue']}">{hop(t['onvalue'], w=16)} Vorziehen</button></div></div>
    <div class="tabs"><span class="act" style="border-color:{t['on']}">Folgen</span><span style="color:{t['onvar']}">Community</span></div>
    {rows}
    <div class="mp" style="background:{t['surface']};border-top:1px solid {t['outline']}"><div class="cover tiny" style="{COVER}"></div>
      <div style="flex:1"><div class="sm b">Folge 296 – Value for Value im Alltag</div><div class="sm" style="color:{t['onvar']}">27:14 / 1:12:05</div></div><span class="b">❚❚</span></div>
    <div class="nav" style="background:{t['bg']};border-top:1px solid {t['outline']}"><span>◉<br>Neu</span><span class="act2">▤<br>Bibliothek</span><span>⌕<br>Entdecken</span></div>
    ''', 'Show-Seite – hell')

def boost_sheet(t):
    recips = [('Nodesignal', 50, True), ('Karsten', 15, True), ('Cutting', 10, False), ('Gast: Anna', 10, True), ('Produktion', 8, True), ('Musik: Wavlake', 5, True), ('Podcastindex', 2, True)]
    shades = ['#0a0a0a', '#3a3935', '#5e5d59', '#7d7b76', '#9a9892', '#b5b3ad', '#cfcdc7'] if t is L else ['#f6f5f2', '#cfcdc7', '#a3a19b', '#7d7b76', '#5e5d59', '#46453f', '#3a3935']
    stripe = f'repeating-linear-gradient(45deg,{t["valuetext"]} 0 3px,transparent 3px 6px)'
    seg = ''.join(f'<div style="flex:{p};background:{shades[i] if ok else stripe}"></div>' for i, (n, p, ok) in enumerate(recips))
    legend = ' · '.join(f'<span style="white-space:nowrap;{"" if ok else "color:" + t["valuetext"]}"><i class="dot" style="background:{shades[i] if ok else t["valuetext"]}"></i>{n} {p} %</span>' for i, (n, p, ok) in enumerate(recips[:4]))
    rl = (f'<div class="splitbar">{seg}</div><div class="legend" style="color:{t["onvar"]}">{legend} · <b style="color:{t["on"]}">+3 weitere ›</b></div>'
          f'<div class="sm" style="color:{t["valuetext"]};margin-top:4px">1 Empfänger nicht erreichbar – sein Anteil wird übersprungen</div>')
    return frame(t, f'''
    <div class="dim"></div>
    <div class="sheet" style="background:{t['surface']}"><div class="grab" style="background:{t['outline']}"></div>
      <div class="ttl" style="margin-top:6px">{hop(t['value'], w=26)} Boost an Nodesignal</div>
      <div class="sm" style="color:{t['onvar']}">Folge 296 · bei 27:14</div>
      <div class="amounts">{''.join(f'<span style="background:{t["value"] if a=="2.100" else t["variant"]};color:{t["onvalue"] if a=="2.100" else t["on"]}">{a}</span>' for a in ('100','500','1.000','2.100','…'))}</div>
      <div class="field" style="background:{t['variant']};color:{t['onvar']}">Nachricht (optional)</div>
      <div class="field" style="background:{t['variant']};color:{t['onvar']}">Dein Name (optional)</div>
      <div class="sm b" style="margin:14px 0 6px">Aufteilung laut Feed · 7 Empfänger</div>{rl}
      <button class="pill wide" style="background:{t['value']};color:{t['onvalue']}">{hop(t['onvalue'])} 2.100 sats boosten</button>
      <div class="sm" style="color:{t['onvar']};text-align:center;margin-top:8px">über Alby Hub (NWC) · Budget heute: 7.900 sats übrig</div></div>
    ''', 'Boost-Dialog – hell')

def community(t):
    sug = [('Folge über Ark und Spark', 34, True, '1.500'), ('Interview mit einem Cashu-Mint-Betreiber', 21, False, None), ('Lightning im Einzelhandel – Praxistest', 12, False, '210')]
    rows = ''
    for a, b, c, d in sug:
        boosted = f' · <span style="color:{t["valuetext"]}">{hop(t["valuetext"], w=12)} {d} sats</span>' if d else ''
        vstyle = f'background:{t["primary"]};color:{t["onprimary"]}' if c else f'border:1.5px solid {t["outline"]}'
        rows += (f'<div class="sug" style="border-bottom:1px solid {t["outline"]}"><div style="flex:1"><div class="b" style="font-size:14px">{a}</div>'
                 f'<div class="sm" style="color:{t["onvar"]}">{b} Stimmen{boosted}</div></div><span class="vote" style="{vstyle}">▲ {b}</span></div>')
    board = ''.join(f'<div class="lb"><span style="color:{t["onvar"]}">{i}</span><span style="flex:1">{n}</span><span style="color:{t["valuetext"]}" class="b">{s} sats</span></div>'
                    for i, (n, s) in enumerate([('satoshi_fan', '21.000'), ('anonym', '12.600'), ('Karla', '8.400')], 1))
    inner = f'''
    <div class="bar"><span class="ic">‹</span><span class="sm" style="color:{t['onvar']}">Nodesignal</span><span class="ic">⋯</span></div>
    <div class="tabs" style="margin-top:0"><span style="color:{t['onvar']}">Folgen</span><span class="act" style="border-color:{t['on']}">Community</span></div>
    <div class="sc" style="background:{t['surface']};border:1px solid {t['outline']};margin-top:8px">
      <div class="sm b" style="color:{t['valuetext']}">BOOST2UNLOCK</div>
      <div class="b" style="margin:4px 0 8px">Bonusfolge: Live vom Meetup</div>
      <div class="track thick" style="background:{t['variant']}"><div class="fill" style="background:{t['value']};width:41%"></div></div>
      <div class="row2"><span class="sm" style="color:{t['onvar']}">8.200 von 20.000 sats · Clip bei 50 %</span>
      <button class="pill small" style="background:{t['value']};color:{t['onvalue']}">{hop(t['onvalue'], w=16)} Freischalten</button></div></div>
    <div class="sec"><span class="b">Themenvorschläge</span><span class="sm b">+ Vorschlagen</span></div>
    {rows}
    <div class="sec"><span class="b">Top-Booster diesen Monat</span><span class="sm" style="color:{t['onvar']}">Alle ›</span></div>
    {board}
    <div class="sm" style="color:{t['onvar']};margin-top:12px;text-align:center">Community-Funktionen über Sovereigncast · pm.nodesignal.space</div>'''
    return frame(t, inner, 'Community-Reiter – hell')


css = '''@font-face{font-family:Figtree;src:url(FONT_URI)}
body{margin:0;padding:20px;background:#d9d7d1;font-family:Figtree;display:flex;gap:26px;align-items:flex-start}
.cap{font:700 14px Figtree;margin:0 0 8px 4px;color:#333}
.phone{width:360px;height:760px;border-radius:38px;overflow:hidden;position:relative;box-shadow:0 10px 30px #0004;padding:0 18px;box-sizing:border-box}
.status{display:flex;justify-content:space-between;font-size:13px;font-weight:700;padding:14px 6px 6px}
.bar{display:flex;justify-content:space-between;align-items:center;margin:4px 0 10px}.ic{font-size:24px}
.cover{border-radius:22px}.big{height:300px;position:relative}.mid{width:104px;height:104px;border-radius:18px;flex:none}.tiny{width:48px;height:48px;border-radius:10px;flex:none}
.chapnav{position:absolute;left:10px;right:10px;bottom:10px;display:flex;align-items:center;gap:4px;background:#000b;color:#fff;border-radius:999px;padding:4px}.cb{width:40px;height:40px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:24px;background:#ffffff1f}.ct{flex:1;text-align:center;font-size:12.5px;line-height:1.3}.chap{position:absolute;left:12px;bottom:12px;background:#000a;color:#fff;font-size:12px;padding:5px 10px;border-radius:999px}
.ttl{font-weight:800;font-size:20px;margin:16px 0 2px;line-height:1.2}.sub{font-size:14px}.sm{font-size:12.5px}.b{font-weight:700}
.prog{margin:16px 0 6px}.track{height:5px;border-radius:9px;position:relative}.track.thick{height:9px}.fill{height:100%;border-radius:9px}
.track i{position:absolute;top:0;width:2px;height:5px}.knob{position:absolute;top:-5px;width:15px;height:15px;border-radius:50%;margin-left:-7px}
.times{display:flex;justify-content:space-between;font-size:12px;margin-top:6px}
.ctl{display:flex;justify-content:space-between;align-items:center;margin:8px 4px 16px}.ic2{font-size:26px}.ic2 sub{font-size:10px}
.play{width:68px;height:68px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:22px}
.value{display:flex;align-items:center;gap:12px;padding:7px 10px 7px 8px;border-radius:20px}.stream{flex:1}
.pill{font:700 15px Figtree;border:0;border-radius:999px;padding:11px 16px;display:inline-flex;align-items:center;gap:7px}
.pill.vb{padding:9px 15px}.pill.small{font-size:13px;padding:7px 13px}.pill.wide{width:100%;justify-content:center;margin-top:16px;padding:14px}
.switch{width:44px;height:26px;border-radius:99px;position:relative}.switch div{position:absolute;right:3px;top:3px;width:20px;height:20px;border-radius:50%}
.sheets{display:flex;gap:6px;margin-top:16px;flex-wrap:wrap}.sheets span{border-radius:999px;padding:7px 12px;font-size:13px}
.head{display:flex;gap:14px;align-items:center;margin-bottom:14px}
.sc{border-radius:20px;padding:12px 14px}.row2{display:flex;justify-content:space-between;align-items:center;margin-top:8px;gap:8px}
.tabs{display:flex;gap:20px;margin:14px 0 4px;font-weight:700;font-size:14px}.tabs span{padding-bottom:6px;border-bottom:2.5px solid transparent}
.ep{display:flex;gap:12px;align-items:center;padding:11px 0}.ep .b{font-size:14px}.mini{width:32px;height:32px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:12px}
.mp{position:absolute;left:0;right:0;bottom:62px;display:flex;gap:10px;align-items:center;padding:8px 18px}
.nav{position:absolute;left:0;right:0;bottom:0;height:62px;display:flex;justify-content:space-around;align-items:center;font-size:11px;text-align:center}.nav .act2{font-weight:800}
.dim{position:absolute;inset:0;background:#0006}
.sheet{position:absolute;left:0;right:0;bottom:0;border-radius:28px 28px 0 0;padding:10px 20px 26px}.grab{width:40px;height:5px;border-radius:9px;margin:0 auto}
.amounts{display:flex;gap:7px;margin:14px 0}.amounts span{flex:1;text-align:center;border-radius:999px;padding:10px 0;font-weight:700;font-size:13.5px}
.field{border-radius:14px;padding:12px 14px;font-size:13.5px;margin-top:8px}
.sec{display:flex;justify-content:space-between;align-items:center;margin:18px 0 4px}.sug{display:flex;gap:10px;align-items:center;padding:10px 0}.vote{border-radius:999px;padding:6px 11px;font-size:12.5px;font-weight:700;white-space:nowrap}.lb{display:flex;gap:10px;font-size:13.5px;padding:5px 0}.splitbar{display:flex;gap:2px;height:10px;border-radius:9px;overflow:hidden}.legend{font-size:12.5px;line-height:1.6;margin-top:6px}.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:4px;vertical-align:1px}.rec{display:grid;grid-template-columns:1fr auto;gap:2px 8px;padding:5px 0;font-size:14px}.rec span:last-child{grid-column:1/3}'''
behind = show_page(L).replace('<div class="cap">Show-Seite – hell</div>','')
html = f'<html><head><style>{css}</style></head><body>{now_playing(L)}{community(L)}{boost_sheet(L)}</body></html>'
html = html.replace('FONT_URI', (HERE / 'Figtree.ttf').as_uri())
out_html = HERE / 'key-screens-draft.html'
out_html.write_text(html)
with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page(viewport={'width':1190,'height':840})
    pg.goto(out_html.as_uri()); pg.wait_for_timeout(500); pg.screenshot(path=str(HERE / 'key-screens-draft-2.png'), full_page=True); b.close()
out_html.unlink()
