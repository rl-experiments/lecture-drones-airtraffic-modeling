"""Compare Mission 3 star flights: how far the logged (Tracker) path stays from the planned lines.

Only the 5 star edges count (legs 2-6), not climb and descent.
Usage: python compare_star.py <log> [<log> ...]
"""
import sys, math, bisect, statistics as st
sys.stdout.reconfigure(encoding='utf-8')


def seg_dist(p, a, b):
    d = [y - x for x, y in zip(a, b)]
    L = sum(x * x for x in d) or 1
    t = max(0, min(1, sum((p[i] - a[i]) * d[i] for i in range(3)) / L))
    return math.dist(p, [a[i] + t * d[i] for i in range(3)])


print(f"{'Log':34} {'Ende':>7} {'Dauer':>6} {'mittl.':>7} {'max':>6} {'Ecke':>6} {'v max':>6} {'Höhe Δ':>7}")
for f in sys.argv[1:]:
    targets, ends, steps, obst = [], [], [], []
    for line in open(f, encoding='utf-8'):
        p = line.split()
        if len(p) < 2:
            continue
        t = int(p[0]); kv = dict(x.split('=', 1) for x in p[2:] if '=' in x)
        if p[1] == 'leg' and p[2] == 'target':
            targets.append((t, (float(kv['n']), float(kv['e']), float(kv['u']))))
        elif p[1] == 'leg':
            ends.append(p[2])
        elif p[1] == 'step':
            steps.append((t, len(targets) - 1, (float(kv['n']), float(kv['e']), float(kv['u'])),
                          math.sqrt(sum(float(kv[k]) ** 2 for k in ('vn', 've', 'vd')))))
        elif p[1] == 'obstacle' and kv.get('down_mm', 'null') != 'null':
            obst.append((t, int(kv['down_mm']) / 1000))
    edges = range(1, 6)
    d_all, tip_err, vmax = [], [], 0
    for leg in edges:
        a, b = targets[leg - 1][1], targets[leg][1]
        pts = [(s[2], s[3]) for s in steps if s[1] == leg]
        d_all += [seg_dist(p, a, b) for p, _ in pts]
        tip_err.append(min(math.dist(p, b) for p, _ in pts))
        vmax = max([vmax] + [v for _, v in pts])
    # overshoot at each tip: how far past the tip, along the incoming line, the drone gets
    over = []
    for leg in edges:
        a, tip = targets[leg - 1][1], targets[leg][1]
        d = [y - x for x, y in zip(a, tip)]; L = math.sqrt(sum(x * x for x in d))
        near = [s[2] for s in steps if s[1] in (leg, leg + 1)]
        over.append(max(0, max(sum((p[i] - tip[i]) * d[i] for i in range(3)) / L for p in near)))
    # height: Tracker u against the downward sensor during the star
    t0, t1 = targets[1][0], targets[6][0] if len(targets) > 6 else steps[-1][0]
    st_t = [s[0] for s in steps]
    dh = []
    for t, h in obst:
        if t0 <= t <= t1 and 0.1 < h < 10:
            i = min(bisect.bisect_left(st_t, t), len(steps) - 1)
            dh.append(abs(steps[i][2][2] - h))
    star_s = (t1 - t0) / 1000
    result = 'ok' if all(e == 'arrived' for e in ends) else f"{sum(e != 'arrived' for e in ends)}x aufg."
    name = f.replace('\\', '/').split('/')[-1]
    print(f"{name:34} {result:>7} {star_s:5.0f}s {st.mean(d_all):6.2f}m {max(d_all):5.2f}m "
          f"{max(over):5.2f}m {vmax:5.2f} {st.median(dh) if dh else float('nan'):6.2f}m")
