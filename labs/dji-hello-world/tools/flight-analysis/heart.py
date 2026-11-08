"""Plan vs. photo of the Mission 2 heart, in the style of slide.py's star figure.

    python heart.py <long-exposure photo> <out.svg>

The plan is FigurePath.kt's heart (14 m letter height, smoothed the same way). It is
laid over the photo by a best fit (turn, scale, shift) done once for the photo of
7 Nov 2026; the photo carries no GPS, so the fit, not the drone, sets the alignment.
"""
import base64, io, math, sys
from PIL import Image

photo, out = sys.argv[1], sys.argv[2]

# The plan, in metres: x to the camera's right, y up from the lowest point.
HEART = [(0.42, 0.0), (0.04, 0.46), (0.0, 0.78), (0.20, 0.94), (0.42, 0.70), (0.42, 0.70),
         (0.64, 0.94), (0.84, 0.78), (0.80, 0.46), (0.42, 0.0), (0.42, 0.0), (0.34, -0.10)]
plan = [(x * 14, (y + 0.1) * 14) for x, y in HEART]
for _ in range(3):  # Chaikin, as FigurePath.smooth
    n = [plan[0]]
    for p, q in zip(plan, plan[1:]):
        n += [(.75 * p[0] + .25 * q[0], .75 * p[1] + .25 * q[1]), (.25 * p[0] + .75 * q[0], .25 * p[1] + .75 * q[1])]
    n.append(plan[-1])
    plan = n
home = [plan[-1], (0.0, 0.0)]  # flat: back above the take-off spot

# Best fit of the plan to the photo of 7 Nov 2026: turn, pixels per metre, centre.
TURN, PX_PER_M, CX, CY = -0.0628, 35.95, 817.95, 616.06
def to_photo(x, y):
    c, s = math.cos(TURN), math.sin(TURN)
    x, y = x - 5.8, y - 7
    return CX + PX_PER_M * (c * x - s * y), CY - PX_PER_M * (s * x + c * y)

TOP, PH = 74, 371.2
PLAN_C = "#d97706"
title = ("The heart of Mission 2, planned and flown flat at 50 m, photographed from below "
         "with a long exposure. The flown heart is about 0.6 metres off the plan on average, "
         "2 metres at most; the left lobe is too big and the notch too low.")
W, H = 1010, TOP + PH + 82   # the legend gets its own row under the axis labels
o = [f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 {W} {H:.0f}" '
     f'font-family="Inter, \'Segoe UI\', Arial, sans-serif" role="img" aria-label="{title}">',
     f'<rect x="0" y="0" width="{W}" height="{H:.0f}" fill="#ffffff"/>']

def poly(pts, colour, width, dash=""):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return (f'<polyline points="{" ".join(f"{x:.1f},{y:.1f}" for x, y in pts)}" fill="none" '
            f'stroke="{colour}" stroke-width="{width}" stroke-linejoin="round"{d}/>')

# Left: the plan in metres.
px, S = 62, PH / 17           # y from -1.5 to 15.5 m
PW = 15 * S                   # x from -1.5 to 13.5 m
X = lambda v: px + (v + 1.5) * S
Y = lambda v: TOP + PH - (v + 1.5) * S
o.append(f'<text x="{px}" y="28" font-size="17" font-weight="600" fill="#171717">Planned</text>')
o.append(f'<text x="{px}" y="52" font-size="14" fill="#737373">FigurePath.kt, 12 × 15 m</text>')
o.append(f'<rect x="{px}" y="{TOP}" width="{PW:.1f}" height="{PH}" fill="#fafafa" stroke="#d4d4d4"/>')
for v in range(0, 14, 2):
    o.append(f'<line x1="{X(v):.1f}" y1="{TOP}" x2="{X(v):.1f}" y2="{TOP + PH}" stroke="#e5e5e5"/>')
    o.append(f'<text x="{X(v):.1f}" y="{TOP + PH + 18}" font-size="12.5" text-anchor="middle" fill="#737373">{v}</text>')
for v in range(0, 16, 2):
    o.append(f'<line x1="{px}" y1="{Y(v):.1f}" x2="{px + PW:.1f}" y2="{Y(v):.1f}" stroke="#e5e5e5"/>')
    o.append(f'<text x="{px - 8}" y="{Y(v) + 4:.1f}" font-size="12.5" text-anchor="end" fill="#737373">{v}</text>')
o.append(f'<text x="{px + PW / 2:.1f}" y="{TOP + PH + 40}" font-size="13.5" text-anchor="middle" fill="#525252">To the camera\'s right, m</text>')
o.append(f'<text transform="translate({px - 32},{TOP + PH / 2}) rotate(-90)" font-size="13.5" text-anchor="middle" fill="#525252">Ahead of the nose, m</text>')
o.append(poly([(X(x), Y(y)) for x, y in home], PLAN_C, 1.6, "2 4"))
o.append(poly([(X(x), Y(y)) for x, y in plan], PLAN_C, 2.4, "6 5"))
o.append(f'<circle cx="{X(0):.1f}" cy="{Y(0):.1f}" r="5" fill="#171717"/>'
         f'<text x="{X(0) + 9:.1f}" y="{Y(0) + 17:.1f}" font-size="12.5" fill="#525252">take-off</text>')

# Right: the photo, cropped, with the plan laid over it.
L, T, R, B = 380, 250, 1130, 935                    # crop in photo pixels
qx = 560
QW = PH * (R - L) / (B - T)
k = PH / (B - T)
img = Image.open(photo).convert("RGB").crop((L, T, R, B))
img = img.resize((round(QW * 2), round(PH * 2)), Image.LANCZOS)
buf = io.BytesIO(); img.save(buf, "JPEG", quality=78)
href = base64.b64encode(buf.getvalue()).decode()
o.append(f'<text x="{qx}" y="28" font-size="17" font-weight="600" fill="#171717">Flown: long exposure from below</text>')
o.append(f'<text x="{qx}" y="52" font-size="14" fill="#737373">Flat at 50 m, 7 Nov 2026. Off the plan: mean 0.6 m, max 2.0 m</text>')
o.append(f'<image x="{qx}" y="{TOP}" width="{QW:.1f}" height="{PH}" href="data:image/jpeg;base64,{href}"/>')
P = lambda x, y: (qx + (to_photo(x, y)[0] - L) * k, TOP + (to_photo(x, y)[1] - T) * k)
o.append(poly([P(x, y) for x, y in home], "#fbbf24", 1.6, "2 4"))
o.append(poly([P(x, y) for x, y in plan], "#fbbf24", 2.4, "6 5"))
hx, hy = P(0, 0)
o.append(f'<circle cx="{hx:.1f}" cy="{hy:.1f}" r="5" fill="#fbbf24"/>')

# Legend.
o.append(f'<line x1="270" y1="{H - 14:.0f}" x2="310" y2="{H - 14:.0f}" stroke="{PLAN_C}" stroke-width="2.4" stroke-dasharray="6 5"/>'
         f'<text x="318" y="{H - 9:.0f}" font-size="13.5" fill="#525252">Planned</text>')
o.append(f'<line x1="396" y1="{H - 14:.0f}" x2="436" y2="{H - 14:.0f}" stroke="{PLAN_C}" stroke-width="1.6" stroke-dasharray="2 4"/>'
         f'<text x="444" y="{H - 9:.0f}" font-size="13.5" fill="#525252">Planned flight back</text>')
o.append(f'<line x1="590" y1="{H - 14:.0f}" x2="630" y2="{H - 14:.0f}" stroke="#a3a3a3" stroke-width="3"/>'
         f'<text x="638" y="{H - 9:.0f}" font-size="13.5" fill="#525252">Flown (light trail)</text>')
o.append('</svg>')
open(out, "w", encoding="utf-8").write("\n".join(o))
