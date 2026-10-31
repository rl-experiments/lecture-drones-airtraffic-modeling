import sys, math
out = sys.argv[1]
files = sys.argv[2:]
COL = ['#5ab0ff', '#7dffb0', '#ff8a5c']
import re, statistics as st
LBL = [re.search(r'_(\d\d)-(\d\d)-(\d\d)', f).expand(r'\1:\2:\3') for f in files]
STAT = []
def seg3(p, a, b):
    d = [y - x for x, y in zip(a, b)]; L = sum(x * x for x in d)
    t = max(0, min(1, sum((p[i] - a[i]) * d[i] for i in range(3)) / L))
    return math.dist(p, [a[i] + t * d[i] for i in range(3)])
for f in files:
    T = []; E = []
    for l in open(f, encoding='utf-8'):
        p = l.split(); kv = dict(x.split('=', 1) for x in p[2:] if '=' in x)
        if p[1] == 'leg' and p[2] == 'target':
            T.append((float(kv['n']), float(kv['e']), float(kv['u'])))
        elif p[1] == 'step' and 1 <= len(T) - 1 <= 3:
            E.append(seg3((float(kv['n']), float(kv['e']), float(kv['u'])), T[-2], T[-1]))
    STAT.append(('Mittel %.1f cm, max. %.1f cm' % (st.mean(E) * 100, max(E) * 100)).replace('.', ',').replace('max,', 'max.'))
fl = []
for f in files:
    legs = []; steps = []; O = []
    for l in open(f, encoding='utf-8'):
        p = l.split(); t = int(p[0]); kv = dict(x.split('=', 1) for x in p[2:] if '=' in x)
        if p[1] == 'leg' and p[2] == 'target':
            legs.append((t, float(kv['n']), float(kv['e']), float(kv['u'])))
        elif p[1] == 'step':
            d = {a: float(b) for a, b in kv.items()}; d['t'] = t; steps.append(d)
        elif p[1] == 'obstacle':
            O.append((t, int(kv['down_mm']) / 1000))
    h = math.radians(steps[0]['heading'])
    R = lambda a, b, h=h: -a * math.sin(h) + b * math.cos(h)
    F = lambda a, b, h=h: a * math.cos(h) + b * math.sin(h)
    for s in steps:
        s['x'] = -R(s['n'], s['e']); s['f'] = F(s['n'], s['e'])
    tips = [(-R(a, b), F(a, b), c) for _, a, b, c in legs[:4]]
    fl.append(dict(steps=steps, tips=tips, t0=legs[0][0], O=O))

W, H = 1180, 1000; S = 150
o = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" font-family="Segoe UI,Arial,sans-serif" font-size="14" fill="#c9d0de">',
     f'<rect width="{W}" height="{H}" fill="#0b0f18"/>',
     '<text x="30" y="36" font-size="22" fill="#fff">Mission 5: drei Flüge übereinander, Dreieck %s Höhenkorrektur</text>' % ('ohne' if any('height_correction off' in open(f, encoding='utf-8').read() for f in files) else 'mit'),
     '<text x="30" y="60" fill="#8b93a7">Aus den Drohnen-Logs · horizontal aus aufsummierter Geschwindigkeit, nicht von außen gemessen</text>']

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
    tips = fl[0]['tips']
    o.append('<polyline points="%s" fill="none" stroke="#fff" stroke-opacity=".6" stroke-width="1.5" stroke-dasharray="6 5"/>' % ' '.join(f'{X(fx(t)):.1f},{Y(fy(t)):.1f}' for t in tips))
    for c, f in zip(COL, fl):
        o.append('<polyline points="%s" fill="none" stroke="%s" stroke-width="2.5" stroke-opacity=".9" stroke-linejoin="round"/>' % (' '.join(f'{X(sf(s)[0]):.1f},{Y(sf(s)[1]):.1f}' for s in f['steps']), c))
    for i, t in enumerate(tips[:3]):
        o.append(f'<circle cx="{X(fx(t)):.1f}" cy="{Y(fy(t)):.1f}" r="10" fill="#fff"/><text x="{X(fx(t)):.1f}" y="{Y(fy(t))+5:.1f}" text-anchor="middle" fill="#05070d" font-weight="bold">{i+1}</text>')

panel(90, 110, 'Blick der Kamera vor der Nase', (-1.6, 1.6), (0.6, 3.2), 'seitlich, m (rechts = links der Drohne)', 'Höhe, m',
      lambda t: t[0], lambda t: t[2], lambda s: (s['x'], s['u']))
panel(680, 110, 'Von oben: Abweichung aus der Ebene', (-1.6, 1.6), (-1.3, 1.3), 'seitlich, m', 'vor/zurück, m',
      lambda t: t[0], lambda t: t[1], lambda s: (s['x'], s['f']))

lx, ly = 680, 545
o.append(f'<line x1="{lx}" y1="{ly}" x2="{lx+40}" y2="{ly}" stroke="#fff" stroke-opacity=".6" stroke-width="1.5" stroke-dasharray="6 5"/><text x="{lx+50}" y="{ly+5}">geplant</text>')
for i, (c, n, st) in enumerate(zip(COL, LBL, STAT)):
    y = ly + 26 * (i + 1)
    o.append(f'<line x1="{lx}" y1="{y}" x2="{lx+40}" y2="{y}" stroke="{c}" stroke-width="3"/><text x="{lx+50}" y="{y+5}">Flug {n}: Abweichung {st}</text>')

tx, ty, tw, th = 90, 690, 1050, 240
tmax = max((f['steps'][-1]['t'] - f['t0']) for f in fl)
TX = lambda t: tx + t / tmax * tw
TY = lambda v: ty + th - (v - .5) / 3.0 * th
o.append(f'<text x="{tx}" y="{ty-14}" font-size="16" fill="#fff">Bodensensor über die Zeit, je Flug ab Beginn der ersten Linie</text><rect x="{tx}" y="{ty}" width="{tw}" height="{th}" fill="#111725" stroke="#2a3142"/>')
for v in (1, 1.5, 2, 2.5, 3):
    o.append(f'<line x1="{tx}" y1="{TY(v):.1f}" x2="{tx+tw}" y2="{TY(v):.1f}" stroke="#222a3a"/><text x="{tx-8}" y="{TY(v)+5:.1f}" text-anchor="end" fill="#8b93a7">{v:g}</text>')
o.append(f'<line x1="{tx}" y1="{TY(1.2):.1f}" x2="{tx+tw}" y2="{TY(1.2):.1f}" stroke="#fff" stroke-opacity=".5" stroke-dasharray="6 5"/><text x="{tx+tw-8}" y="{TY(1.2)-6:.1f}" text-anchor="end" fill="#8b93a7">untere Linie: Soll 1,2 m</text>')
o.append(f'<text transform="translate({tx-46},{ty+th/2}) rotate(-90)" text-anchor="middle">Höhe, m</text>')
for c, f in zip(COL, fl):
    pts = [(t - f['t0'], d) for t, d in f['O'] if 0 <= t - f['t0'] <= f['steps'][-1]['t'] - f['t0'] and 0 < d < 30]
    o.append('<polyline points="%s" fill="none" stroke="%s" stroke-width="2.2"/>' % (' '.join(f'{TX(a):.1f},{TY(b):.1f}' for a, b in pts), c))
for i in range(0, int(tmax / 1000) + 1, 5):
    o.append(f'<text x="{TX(i*1000):.1f}" y="{ty+th+20}" text-anchor="middle" fill="#8b93a7">{i} s</text>')
o.append('</svg>')
open(out, 'w', encoding='utf-8').write('\n'.join(o))
