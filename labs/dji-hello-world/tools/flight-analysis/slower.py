import sys, bisect
lab, out = sys.argv[1], sys.argv[2]

def load(f):
    S = []; O = []; t0 = None
    for l in open(f, encoding='utf-8'):
        p = l.split(); t = int(p[0]); kv = dict(x.split('=', 1) for x in p[2:] if '=' in x)
        if p[1] == 'leg' and p[2] == 'target' and t0 is None:
            t0 = t
        elif p[1] == 'step':
            S.append((t, float(kv['u'])))
        elif p[1] == 'obstacle':
            d = int(kv['down_mm']) / 1000
            if 0 < d < 30:
                O.append((t, d))
    end = S[-1][0]
    O = [(t, d) for t, d in O if t0 <= t <= end]
    ts = [s[0] for s in S]
    worst = (0, 0, 0, 0)
    for t, d in O:
        i = bisect.bisect(ts, t) - 1
        if i >= 0 and t - ts[i] <= 300 and abs(S[i][1] - d) > abs(worst[2]):
            worst = (t, d, S[i][1] - d, S[i][1])
    return [((t - t0) / 1000, u) for t, u in S], [((t - t0) / 1000, d) for t, d in O], (end - t0) / 1000, ((worst[0] - t0) / 1000, worst[1], worst[2], worst[3])

fast = load(lab + '/mission-3-2026-10-31_11-59-47.log')
slow = load(lab + '/mission-3-2026-10-31_12-38-23.log')
print('fast worst', fast[3], 'dur', fast[2]); print('slow worst', slow[3], 'dur', slow[2])

W, H = 1120, 480; TOP, PH = 76, 330; PW = 470; YMAX = 5.0
BLUE, ORANGE = '#2563eb', '#ea580c'
aria = "Height over time in two flights of the same star. Flown fast, the height the app calculated matches the ground sensor to within about 17 centimetres. Flown slowly, the app is about 1.3 metres off at the top."
o = [f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" font-family="Inter, 'Segoe UI', Arial, sans-serif" role="img" aria-label="{aria}">''',
     f'<rect x="0" y="0" width="{W}" height="{H}" fill="#ffffff"/>']

def panel(px, title, sub, data, step):
    S, O, dur, w = data
    X = lambda t: px + t / dur * PW
    Y = lambda v: TOP + PH - v / YMAX * PH
    o.append(f'<text x="{px}" y="28" font-size="17" font-weight="600" fill="#171717">{title}</text>')
    o.append(f'<text x="{px}" y="52" font-size="14" fill="#737373">{sub}</text>')
    o.append(f'<rect x="{px}" y="{TOP}" width="{PW}" height="{PH}" fill="#fafafa" stroke="#d4d4d4"/>')
    for v in range(0, 6):
        o.append(f'<line x1="{px}" y1="{Y(v):.1f}" x2="{px+PW}" y2="{Y(v):.1f}" stroke="#e5e5e5"/><text x="{px-8}" y="{Y(v)+4:.1f}" font-size="12.5" text-anchor="end" fill="#737373">{v}</text>')
    t = 0
    while t <= dur:
        o.append(f'<text x="{X(t):.1f}" y="{TOP+PH+18}" font-size="12.5" text-anchor="middle" fill="#737373">{t}</text>')
        t += step
    o.append(f'<text x="{px+PW/2}" y="{TOP+PH+40}" font-size="13.5" text-anchor="middle" fill="#525252">Seconds</text>')
    o.append(f'<text transform="translate({px-32},{TOP+PH/2}) rotate(-90)" font-size="13.5" text-anchor="middle" fill="#525252">Height, m</text>')
    o.append('<polyline points="%s" fill="none" stroke="%s" stroke-width="2.5" stroke-linejoin="round"/>' % (' '.join(f'{X(a):.1f},{Y(b):.1f}' for a, b in O), ORANGE))
    o.append('<polyline points="%s" fill="none" stroke="%s" stroke-width="2.5" stroke-linejoin="round"/>' % (' '.join(f'{X(a):.1f},{Y(b):.1f}' for a, b in S), BLUE))
    return X, Y

panel(62, 'Flown fast: the app and the sensor agree', 'The same star, about 25 s. Gap at most 0.17 m', fast, 5)
X, Y = panel(610, 'Flown slowly: the app loses track', 'The same star, about 92 s. Gap about 1.3 m at the top', slow, 20)
SS, OO = slow[0], slow[1]
win = [d for a, d in OO if 78 <= a <= 82]; t = 80.0
d = sum(win) / len(win)
u = min(SS, key=lambda s: abs(s[0] - t))[1]
print('plateau sensor mean %.2f app %.2f gap %.2f n=%d, sensor range %.2f..%.2f' % (d, u, d - u, len(win), min(win), max(win)))
print('around the dip:', [(round(a, 1), round(b, 2)) for a, b in OO if 52 <= a <= 57])
print('app around the dip:', [(round(a, 1), round(b, 2)) for a, b in SS if 52 <= a <= 57][::4])
o.append(f'<line x1="{X(t):.1f}" y1="{Y(d):.1f}" x2="{X(t):.1f}" y2="{Y(u):.1f}" stroke="#171717" stroke-width="1.6"/>')
o.append(f'<text x="{X(t)-8:.1f}" y="{Y(3.25)+5:.1f}" font-size="14" font-weight="600" text-anchor="end" fill="#171717">{d-u:.1f} m</text>')
y = H - 12
o.append(f'<line x1="385" y1="{y-5}" x2="425" y2="{y-5}" stroke="{BLUE}" stroke-width="3"/><text x="433" y="{y}" font-size="13.5" fill="#525252">Calculated by the app</text>')
o.append(f'<line x1="605" y1="{y-5}" x2="645" y2="{y-5}" stroke="{ORANGE}" stroke-width="3"/><text x="653" y="{y}" font-size="13.5" fill="#525252">Measured by the ground sensor</text>')
o.append('</svg>')
open(out, 'w', encoding='utf-8').write('\n'.join(o))
