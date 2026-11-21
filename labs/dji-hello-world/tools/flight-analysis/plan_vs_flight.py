"""Planned path (leg targets) against the flown path (step positions) of one or more mission logs.

The figure plane is found from each log's leg targets, so upright and flat figures both work,
and flights with a different heading land on top of each other.
Usage: python plan_vs_flight.py <mission.log> [<mission.log> ...] <out.png>
"""
import sys, math
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

*logs, out = sys.argv[1:]
COLORS = ['#1f77b4', '#d62728', '#2ca02c', '#9467bd', '#ff7f0e']


def load(src):
    plan, steps = [], []
    for line in open(src, encoding='utf-8'):
        p = line.split()
        if len(p) < 3:
            continue
        kv = dict(x.split('=', 1) for x in p[2:] if '=' in x)
        if p[1] == 'leg' and p[2] == 'target':
            if not plan:   # old logs have no from_*: the climb starts straight above the take-off spot
                plan.append((float(kv.get('from_n', 0)), float(kv.get('from_e', 0)), float(kv.get('from_u', 1.2))))
            plan.append((float(kv['n']), float(kv['e']), float(kv['u'])))
        elif p[1] == 'step':
            steps.append((int(p[0]), float(kv['n']), float(kv['e']), float(kv['u'])))
    if steps and 'from_u' not in open(src, encoding='utf-8').read():
        plan[0] = (0.0, 0.0, steps[0][3])
    # horizontal axis of the figure: the longest horizontal spread between leg targets,
    # turned so the first planned tip after the climb lies on the right
    a, b = max(((a, b) for a in plan for b in plan), key=lambda ab: math.hypot(ab[0][0] - ab[1][0], ab[0][1] - ab[1][1]))
    n, e = b[0] - a[0], b[1] - a[1]
    L = math.hypot(n, e) or 1
    n, e = n / L, e / L
    if len(plan) > 2 and plan[2][0] * n + plan[2][1] * e < 0:
        n, e = -n, -e
    side = lambda p: p[0] * n + p[1] * e
    return plan, steps, side


fig, ax = plt.subplots(figsize=(8, 8))
for k, src in enumerate(logs):
    plan, steps, side = load(src)
    name = src.replace('\\', '/').split('/')[-1].removesuffix('.log')
    c = COLORS[k % len(COLORS)]
    if k == 0:
        ax.plot([side(p) for p in plan], [p[2] for p in plan], '--', color='#888', lw=2, label='Plan', zorder=1)
        ax.scatter([side(p) for p in plan], [p[2] for p in plan], s=50, facecolor='white', edgecolor='#555', zorder=3)
    dur = (steps[-1][0] - steps[0][0]) / 1000
    ax.plot([side(s[1:]) for s in steps], [s[3] for s in steps], color=c, lw=1.8, alpha=.85, zorder=2,
            label=f'{name} ({dur:.0f} s)')
ax.set_aspect('equal')
ax.grid(alpha=.3)
ax.set_xlabel('Seitlich in der Figurebene [m]')
ax.set_ylabel('Höhe [m]')
ax.set_title('Mission 3, Stern aufrecht: Plan und geflogene Bahn laut Drohnen-Log\n'
             'Position aus aufsummierter Geschwindigkeit, nicht von außen gemessen', fontsize=10)
ax.legend(loc='upper right', fontsize=9)
fig.tight_layout()
fig.savefig(out, dpi=130)
print(out)
