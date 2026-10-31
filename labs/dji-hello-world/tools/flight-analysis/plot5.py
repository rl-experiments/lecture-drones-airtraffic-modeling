import sys, math
sys.stdout.reconfigure(encoding='utf-8')
src, out = sys.argv[1], sys.argv[2]
legs = []; ends = []; steps = []; rej = []; O = []
for l in open(src, encoding='utf-8'):
    p = l.split(); t = int(p[0]); kv = dict(x.split('=', 1) for x in p[2:] if '=' in x)
    if p[1] == 'leg' and p[2] == 'target':
        legs.append((t, float(kv['n']), float(kv['e']), float(kv['u'])))
    elif p[1] == 'leg':
        ends.append((t, p[2], float(kv['dist'])))
    elif p[1] == 'step':
        d = {a: float(b) for a, b in kv.items()}; d['t'] = t; d['leg'] = len(legs) - 1; steps.append(d)
    elif p[1] == 'height' and p[2] == 'rejected':
        rej.append((t, float(kv['sensor'])))
    elif p[1] == 'obstacle':
        O.append((t, kv))
h = math.radians(steps[0]['heading'])
right = lambda a, b: -a * math.sin(h) + b * math.cos(h)
fwd = lambda a, b: a * math.cos(h) + b * math.sin(h)
for s in steps:
    s['x'] = -right(s['n'], s['e']); s['f'] = fwd(s['n'], s['e'])
complete = len(legs) == 5
tips = [(-right(a, b), fwd(a, b), c) for _, a, b, c in legs[:4]]
names = ['Start→1', '1→2', '2→3', '3→1', '1→Ende']
W, H_ = 1180, 1000; S = 150

def color(v):
    v = min(v / 1.6, 1)
    stops = [(0, (70, 130, 255)), (.5, (90, 230, 140)), (.75, (255, 210, 70)), (1, (255, 80, 70))]
    for (a, c), (b, d) in zip(stops, stops[1:]):
        if v <= b:
            k = (v - a) / (b - a)
            return '#%02x%02x%02x' % tuple(int(c[i] + (d[i] - c[i]) * k) for i in range(3))

o = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H_}" font-family="Segoe UI,Arial,sans-serif" font-size="14" fill="#c9d0de">',
     f'<rect width="{W}" height="{H_}" fill="#0b0f18"/>',
     '<text x="30" y="36" font-size="22" fill="#fff">Mission 5: Dreieck mit Höhenkorrektur, laut Drohnen-Log</text>',
     f'<text x="30" y="60" fill="#8b93a7">{src.split("/")[-1]} · horizontal aus aufsummierter Geschwindigkeit, nicht von außen gemessen</text>']

def panel(px, py, title, xr, yr, xl, yl, fx, fy, sf):
    pw, ph = (xr[1] - xr[0]) * S, (yr[1] - yr[0]) * S
    X = lambda v: px + (v - xr[0]) * S
    Y = lambda v: py + ph - (v - yr[0]) * S
    o.append(f'<text x="{px}" y="{py-14}" font-size="16" fill="#fff">{title}</text><rect x="{px}" y="{py}" width="{pw}" height="{ph}" fill="#111725" stroke="#2a3142"/>')
    v = math.ceil(xr[0] * 2) / 2
    while v <= xr[1]:
        o.append(f'<line x1="{X(v):.1f}" y1="{py}" x2="{X(v):.1f}" y2="{py+ph}" stroke="#222a3a"/><text x="{X(v):.1f}" y="{py+ph+18}" text-anchor="middle" fill="#8b93a7">{v:g}</text>'); v += .5
    v = math.ceil(yr[0] * 2) / 2
    while v <= yr[1]:
        o.append(f'<line x1="{px}" y1="{Y(v):.1f}" x2="{px+pw}" y2="{Y(v):.1f}" stroke="#222a3a"/><text x="{px-8}" y="{Y(v)+5:.1f}" text-anchor="end" fill="#8b93a7">{v:g}</text>'); v += .5
    o.append(f'<text x="{px+pw/2}" y="{py+ph+40}" text-anchor="middle">{xl}</text><text transform="translate({px-42},{py+ph/2}) rotate(-90)" text-anchor="middle">{yl}</text>')
    pts = ' '.join(f'{X(fx(t)):.1f},{Y(fy(t)):.1f}' for t in tips)
    o.append(f'<polyline points="{pts}" fill="none" stroke="#fff" stroke-opacity=".55" stroke-width="1.5" stroke-dasharray="6 5"/>')
    for a, b in zip(steps, steps[1:]):
        v = math.sqrt(b['vn'] ** 2 + b['ve'] ** 2 + b['vd'] ** 2)
        o.append(f'<line x1="{X(sf(a)[0]):.1f}" y1="{Y(sf(a)[1]):.1f}" x2="{X(sf(b)[0]):.1f}" y2="{Y(sf(b)[1]):.1f}" stroke="{color(v)}" stroke-width="3.5" stroke-linecap="round"/>')
    for i, t in enumerate(tips[:3]):
        o.append(f'<circle cx="{X(fx(t)):.1f}" cy="{Y(fy(t)):.1f}" r="10" fill="#fff"/><text x="{X(fx(t)):.1f}" y="{Y(fy(t))+5:.1f}" text-anchor="middle" fill="#05070d" font-weight="bold">{i+1}</text>')

panel(90, 110, 'Blick der Kamera vor der Nase', (-1.6, 1.6), (0.6, 3.2), 'seitlich, m (rechts = links der Drohne)', 'Höhe, m',
      lambda t: t[0], lambda t: t[2], lambda s: (s['x'], s['u']))
panel(680, 110, 'Von oben: Abweichung aus der Ebene', (-1.6, 1.6), (-1.3, 1.3), 'seitlich, m', 'vor/zurück, m',
      lambda t: t[0], lambda t: t[1], lambda s: (s['x'], s['f']))
lx, ly = 680, 560
o.append(f'<text x="{lx}" y="{ly-10}">Tempo gesamt, m/s</text>')
for i in range(61):
    o.append(f'<rect x="{lx+i*5}" y="{ly}" width="5.5" height="12" fill="{color(i/60*1.6)}"/>')
for v in (0, .8, 1.6):
    o.append(f'<text x="{lx+v/1.6*300}" y="{ly+30}" text-anchor="middle" fill="#8b93a7">{v:g}</text>')
o.append(f'<line x1="{lx}" y1="{ly+55}" x2="{lx+40}" y2="{ly+55}" stroke="#fff" stroke-opacity=".55" stroke-width="1.5" stroke-dasharray="6 5"/><text x="{lx+50}" y="{ly+60}">geplant</text>')

tx, ty, tw, th = 90, 690, 1050, 240
t0, t1 = legs[0][0], steps[-1]['t']
TX = lambda t: tx + (t - t0) / (t1 - t0) * tw
TY = lambda v: ty + th - (v - .5) / 3.0 * th
o.append(f'<text x="{tx}" y="{ty-14}" font-size="16" fill="#fff">Höhe über die Zeit: Bodensensor (gelb), App (grün), Soll (weiß gestrichelt), verworfen (rot)</text><rect x="{tx}" y="{ty}" width="{tw}" height="{th}" fill="#111725" stroke="#2a3142"/>')
for v in (1, 1.5, 2, 2.5, 3):
    o.append(f'<line x1="{tx}" y1="{TY(v):.1f}" x2="{tx+tw}" y2="{TY(v):.1f}" stroke="#222a3a"/><text x="{tx-8}" y="{TY(v)+5:.1f}" text-anchor="end" fill="#8b93a7">{v:g}</text>')
for i, (t, *_) in enumerate(legs):
    e = legs[i + 1][0] if i + 1 < len(legs) else t1
    label = names[i] if i < len(names) else ''
    o.append(f'<line x1="{TX(t):.1f}" y1="{ty}" x2="{TX(t):.1f}" y2="{ty+th}" stroke="#55607a" stroke-dasharray="3 4"/><text x="{(TX(t)+TX(e))/2:.1f}" y="{ty+18}" text-anchor="middle">{label}</text>')
pl = [(t0, steps[0]['u'])]
for i, (t, a, b, c) in enumerate(legs):
    e = [x for x in ends if x[0] > t]
    pl.append(((e[0][0] if e else t1), c))
o.append('<polyline points="%s" fill="none" stroke="#fff" stroke-opacity=".6" stroke-dasharray="6 5"/>' % ' '.join(f'{TX(a):.1f},{TY(b):.1f}' for a, b in pl))
sensor = [(t, int(k['down_mm']) / 1000) for t, k in O if t0 <= t <= t1 and 0 < int(k['down_mm']) < 30000]
o.append('<polyline points="%s" fill="none" stroke="#ffd246" stroke-width="2"/>' % ' '.join(f'{TX(a):.1f},{TY(min(b,3.5)):.1f}' for a, b in sensor))
o.append('<polyline points="%s" fill="none" stroke="#7dffb0" stroke-width="2.5"/>' % ' '.join(f"{TX(s['t']):.1f},{TY(s['u']):.1f}" for s in steps))
for t, m in rej:
    if t0 <= t <= t1:
        o.append(f'<circle cx="{TX(t):.1f}" cy="{TY(min(m,3.5)):.1f}" r="3" fill="#ff5046"/>')
for i in range(0, int((t1 - t0) / 1000) + 1, 5):
    o.append(f'<text x="{TX(t0+i*1000):.1f}" y="{ty+th+20}" text-anchor="middle" fill="#8b93a7">{i} s</text>')
o.append('</svg>')
open(out, 'w', encoding='utf-8').write('\n'.join(o))
