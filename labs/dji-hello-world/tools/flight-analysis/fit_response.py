"""How the Mini 4 Pro answers the virtual sticks, measured from the flight logs.

For each axis (forward, right, up) it fits the simplest model that has the two effects the
logs show, a dead time and a smooth lag:

    v'(t) = (K * stick(t - D) - v(t)) / TAU

K is m/s per stick unit, D the dead time in s, TAU the lag in s. The fit replays every logged
stick value and compares the model's velocity with the velocity the drone reported, rounded the
way the drone rounds it. The numbers go into FakeDrone (app/src/test) so the unit tests fly a
drone that answers like the real one.

    python fit_response.py <log> [<log> ...]
"""
import math
import sys


def load(path):
    """Step lines as (t_s, forward, right, up sticks, forward, right, up velocities)."""
    rows = []
    for line in open(path, encoding="utf-8"):
        p = line.split()
        if len(p) < 3 or p[1] != "step":
            continue
        kv = dict(x.split("=", 1) for x in p[2:] if "=" in x)
        h = math.radians(float(kv["heading"]))
        vn, ve, vd = float(kv["vn"]), float(kv["ve"]), float(kv["vd"])
        rows.append((
            int(p[0]) / 1000,
            int(kv["stick_fwd"]), int(kv["stick_right"]), int(kv["stick_up"]),
            vn * math.cos(h) + ve * math.sin(h),       # north/east to the drone's forward
            -vn * math.sin(h) + ve * math.cos(h),      # ... and right
            -vd,                                       # z points down
        ))
    return rows


def legs(rows, gap_s=0.5):
    """Splits the steps into runs without gaps (each leg is its own run)."""
    run = [rows[0]]
    for r in rows[1:]:
        if r[0] - run[-1][0] > gap_s:
            yield run
            run = []
        run.append(r)
    yield run


def simulate(run, axis, k, d, tau):
    """Model velocity at each step of [run] for stick column [axis]."""
    t0 = run[0][0]
    out, v = [], 0.0
    sticks = [(r[0], r[1 + axis]) for r in run]
    j = 0
    for i, r in enumerate(run):
        if i:
            dt = r[0] - run[i - 1][0]
            while j + 1 < len(sticks) and sticks[j + 1][0] <= r[0] - d:
                j += 1
            s = sticks[j][1] if sticks[j][0] <= r[0] - d else 0
            # Exact step of a first-order lag over dt.
            a = math.exp(-dt / tau)
            v = k * s + (v - k * s) * a
        out.append(v)
    return out


def fit(runs, axis):
    best = None
    for d10 in range(0, 9):
        d = d10 / 10
        for tau in [x / 20 for x in range(1, 41)]:
            # The model is linear in K: fit it by least squares for this D and TAU.
            num = den = 0.0
            for run in runs:
                unit = simulate(run, axis, 1.0, d, tau)
                for m, r in zip(unit, run):
                    num += m * r[4 + axis]
                    den += m * m
            if den == 0:
                continue
            k = num / den
            err = n = 0
            for run in runs:
                for m, r in zip(simulate(run, axis, k, d, tau), run):
                    err += (m - r[4 + axis]) ** 2
                    n += 1
            rms = math.sqrt(err / n)
            if best is None or rms < best[0]:
                best = (rms, k, d, tau, n)
    return best


def main(paths):
    runs = []
    for p in paths:
        rows = load(p)
        if rows:
            runs.extend(legs(rows))
    print(f"{len(paths)} logs, {len(runs)} legs, {sum(len(r) for r in runs)} steps")
    for axis, name in enumerate(["forward", "right", "up"]):
        active = [r for r in runs if any(x[1 + axis] for x in r)]
        rms, k, d, tau, n = fit(active, axis)
        print(f"{name:8} K={k:.4f} m/s per stick (= {1 / k:5.1f} stick per m/s)  "
              f"D={d:.1f} s  TAU={tau:.2f} s  rms={rms:.3f} m/s over {n} steps")


if __name__ == "__main__":
    main(sys.argv[1:])
