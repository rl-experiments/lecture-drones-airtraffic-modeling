import sys, math, statistics as st, bisect
sys.stdout.reconfigure(encoding='utf-8')

def seg(p, a, b):
    d = [y - x for x, y in zip(a, b)]; L = sum(x * x for x in d)
    t = max(0, min(1, sum((p[i] - a[i]) * d[i] for i in range(3)) / L))
    return math.dist(p, [a[i] + t * d[i] for i in range(3)])

for f in sys.argv[1:]:
    T = []; S = []; E = []; hs = []; rj = 0; O = []; hdr = []
    for l in open(f, encoding='utf-8'):
        p = l.split(); t = int(p[0]); kv = dict(x.split('=', 1) for x in p[2:] if '=' in x)
        if p[1] == 'leg' and p[2] == 'target':
            T.append((t, float(kv['n']), float(kv['e']), float(kv['u'])))
        elif p[1] == 'leg':
            E.append((p[2], float(kv['dist'])))
        elif p[1] == 'step':
            d = {a: float(b) for a, b in kv.items()}; d['t'] = t; d['leg'] = len(T) - 1; S.append(d)
        elif p[1] == 'height' and p[2] == 'rejected':
            rj += 1
        elif p[1] == 'height':
            hs.append((t, float(kv['sensor']), float(kv['before']), float(kv['after'])))
        elif p[1] == 'obstacle':
            O.append((t, kv))
        else:
            hdr.append(l.strip())
    print('\n==', f, hdr, 'legs', len(T), 'ends', E)
    if not S:
        print('NO STEPS'); continue
    print(' span %.1fs steps %d; height used %d rejected %d; max dt %.0f' % ((S[-1]['t'] - S[0]['t']) / 1000, len(S), len(hs), rj, max(s['dt_ms'] for s in S)))
    ts = [s['t'] for s in S]; R = []
    for t, k in O:
        d = int(k['down_mm']) / 1000; i = bisect.bisect(ts, t) - 1
        if 0 <= i and t - ts[i] <= 300 and 0 < d < 30:
            R.append((t, S[i]['u'], d, S[i]['leg']))
    e = [r[1] - r[2] for r in R]
    print(' tracked-sensor mean %+.3f sd %.3f min %+.2f max %+.2f n=%d; sensor max %.2f' % (st.mean(e), st.pstdev(e), min(e), max(e), len(e), max(r[2] for r in R)))
    if hs:
        print(' correction |after-before| mean %.3f max %.3f m' % (st.mean(abs(h[3] - h[2]) for h in hs), max(abs(h[3] - h[2]) for h in hs)))
    near = [k for t, k in O if t > S[0]['t'] and t < S[-1]['t'] and k['nearest_mm'] not in ('60000', 'null')]
    print(' near horizontal readings during flight:', len(near), near[:3])
    all_ = []
    for i in range(1, len(T) - 1):
        L = [s for s in S if s['leg'] == i]
        if not L:
            continue
        a, b = T[i - 1][1:], T[i][1:]
        ee = [seg((s['n'], s['e'], s['u']), a, b) for s in L]; all_ += ee
        v = [math.hypot(s['vn'], s['ve']) for s in L]
        pk = sum(1 for x, y, w in zip(v, v[1:], v[2:]) if y > x and y >= w and y > .2)
        msg = '  line %d: mean %.1f max %.1f cm, vh max %.2f, peaks %d, dur %.1fs' % (i, st.mean(ee) * 100, max(ee) * 100, max(v), pk, (L[-1]['t'] - L[0]['t']) / 1000)
        if abs(a[2] - b[2]) < .05:
            rr = [r for r in R if r[3] == i]
            msg += ' LEVEL target %.2f: sensor %.2f..%.2f (mean %.2f), tracked %.2f..%.2f, stick_up %.0f..%.0f' % (a[2], min(r[2] for r in rr), max(r[2] for r in rr), st.mean(r[2] for r in rr), min(s['u'] for s in L), max(s['u'] for s in L), min(s['stick_up'] for s in L), max(s['stick_up'] for s in L))
        print(msg)
    if all_:
        print('  ALL mean %.1f rms %.1f max %.1f cm' % (st.mean(all_) * 100, math.sqrt(sum(x * x for x in all_) / len(all_)) * 100, max(all_) * 100))
