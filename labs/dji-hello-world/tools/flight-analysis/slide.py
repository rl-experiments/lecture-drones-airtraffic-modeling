import sys,math
lab,out=sys.argv[1],sys.argv[2]
def load(f):
    legs=[];steps=[]
    for l in open(f):
        p=l.split();kv=dict(x.split('=') for x in p[2:] if '=' in x)
        if p[1]=='leg' and p[2]=='target': legs.append(tuple(float(kv[k]) for k in 'neu'))
        elif p[1]=='step': steps.append({k:float(v) for k,v in kv.items()})
    h=math.radians(steps[0]['heading'])
    R=lambda n,e:-n*math.sin(h)+e*math.cos(h)
    F=lambda n,e:n*math.cos(h)+e*math.sin(h)
    return [(R(n,e),F(n,e),u) for n,e,u in legs[:6]],[(R(s['n'],s['e']),F(s['n'],s['e']),s['u']) for s in steps]
S=128;PH=2.9*S;PW=3.2*S;TOP=74
o=[]
title="Two stars planned and flown, from the drone's own log. The upright star: mean deviation 7.4 centimetres, maximum 16.5. The flat star: mean 8.3 centimetres, maximum 48.8, overshooting the first tip by about half a metre."
W,H=1120,TOP+PH+58
o.append(f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H:.0f}" font-family="Inter, 'Segoe UI', Arial, sans-serif" role="img" aria-label="{title}">''')
o.append(f'<rect x="0" y="0" width="{W}" height="{H:.0f}" fill="#ffffff"/>')
def panel(px,t,sub,tips,steps,fx,fy,xlab,ylab,y0):
    X=lambda v:px+(v+1.6)*S
    Y=lambda v:TOP+PH-(v-y0)*S
    o.append(f'<text x="{px}" y="28" font-size="17" font-weight="600" fill="#171717">{t}</text>')
    o.append(f'<text x="{px}" y="52" font-size="14" fill="#737373">{sub}</text>')
    o.append(f'<rect x="{px}" y="{TOP}" width="{PW}" height="{PH}" fill="#fafafa" stroke="#d4d4d4"/>')
    v=-1.5
    while v<=1.5:
        o.append(f'<line x1="{X(v):.1f}" y1="{TOP}" x2="{X(v):.1f}" y2="{TOP+PH}" stroke="#e5e5e5"/>')
        if v==int(v): o.append(f'<text x="{X(v):.1f}" y="{TOP+PH+18}" font-size="12.5" text-anchor="middle" fill="#737373">{v:g}</text>')
        v+=.5
    v=math.ceil(y0*2)/2
    while v<=y0+2.9:
        o.append(f'<line x1="{px}" y1="{Y(v):.1f}" x2="{px+PW}" y2="{Y(v):.1f}" stroke="#e5e5e5"/>')
        if v==int(v): o.append(f'<text x="{px-8}" y="{Y(v)+4:.1f}" font-size="12.5" text-anchor="end" fill="#737373">{v:g}</text>')
        v+=.5
    o.append(f'<text x="{px+PW/2}" y="{TOP+PH+40}" font-size="13.5" text-anchor="middle" fill="#525252">{xlab}</text>')
    o.append(f'<text transform="translate({px-32},{TOP+PH/2}) rotate(-90)" font-size="13.5" text-anchor="middle" fill="#525252">{ylab}</text>')
    pts=' '.join(f'{X(fx(q)):.1f},{Y(fy(q)):.1f}' for q in tips)
    o.append(f'<polyline points="{pts}" fill="none" stroke="#737373" stroke-width="1.6" stroke-dasharray="6 5"/>')
    pts=' '.join(f'{X(fx(q)):.1f},{Y(fy(q)):.1f}' for q in steps)
    o.append(f'<polyline points="{pts}" fill="none" stroke="#2563eb" stroke-width="3" stroke-linejoin="round" stroke-linecap="round"/>')
    for i,q in enumerate(tips[:5]):
        o.append(f'<circle cx="{X(fx(q)):.1f}" cy="{Y(fy(q)):.1f}" r="10" fill="#171717"/><text x="{X(fx(q)):.1f}" y="{Y(fy(q))+4.5:.1f}" font-size="12.5" font-weight="600" text-anchor="middle" fill="#ffffff">{i+1}</text>')
t3,s3=load(lab+'/mission-3-2026-10-31_11-59-47.log')
t4,s4=load(lab+'/mission-4-2026-10-31_12-23-06.log')
panel(62,'Upright star','Seen by a camera in front of the nose. Off the line: mean 7.4 cm, max 16.5 cm',t3,s3,lambda q:-q[0],lambda q:q[2],'Sideways, m','Height, m',0.7)
panel(652,'Flat star','Seen from above, nose pointing up. Off the line: mean 8.3 cm, max 48.8 cm',t4,s4,lambda q:q[0],lambda q:q[1],"Drone's right, m",'Ahead of the nose, m',-1.2)
# legend
o.append(f'<line x1="500" y1="{H-14:.0f}" x2="540" y2="{H-14:.0f}" stroke="#737373" stroke-width="1.6" stroke-dasharray="6 5"/><text x="548" y="{H-9:.0f}" font-size="13.5" fill="#525252">Planned</text>')
o.append(f'<line x1="616" y1="{H-14:.0f}" x2="656" y2="{H-14:.0f}" stroke="#2563eb" stroke-width="3"/><text x="664" y="{H-9:.0f}" font-size="13.5" fill="#525252">Flown</text>')
o.append('</svg>')
open(out,'w',encoding='utf-8').write('\n'.join(o))
