import sys,math,statistics as st,bisect,collections
sys.stdout.reconfigure(encoding='utf-8')
def load(f):
    R={'step':[],'obs':[],'legs':[],'hdr':[],'bad':0}
    for l in open(f,encoding='utf-8'):
        p=l.split()
        try:t=int(p[0])
        except: R['bad']+=1;continue
        kv=dict(x.split('=',1) for x in p[2:] if '=' in x)
        if p[1]=='step': R['step'].append(dict(t=t,**{k:float(v) for k,v in kv.items()},leg=len(R['legs'])-1))
        elif p[1]=='obstacle': R['obs'].append((t,kv))
        elif p[1]=='leg': R['legs'].append((t,p[2],kv)) if p[2]=='target' else R.__setitem__('legs',R['legs']) or R.setdefault('ends',[]).append((t,p[2],kv))
        else: R['hdr'].append(l.strip())
    return R
def seg(p,a,b):
    d=[y-x for x,y in zip(a,b)];L=sum(x*x for x in d)
    t=max(0,min(1,sum((p[i]-a[i])*d[i] for i in range(3))/L))
    return math.dist(p,[a[i]+t*d[i] for i in range(3)])
for f in sys.argv[1:]:
    r=load(f);S=r['step'];print('\n=====',f,'lines ok; bad',r['bad'],r['hdr'])
    print('flight span s %.1f steps %d legs %d ends %s'%((S[-1]['t']-S[0]['t'])/1000,len(S),len(r['legs']),[e[1] for e in r['ends']]))
    print('dt_ms median %.0f p99 %.0f max %.0f; heading %.1f..%.1f'%(st.median(s['dt_ms'] for s in S),sorted(s['dt_ms'] for s in S)[int(.99*len(S))],max(s['dt_ms'] for s in S),min(s['heading'] for s in S),max(s['heading'] for s in S)))
    # velocity resolution
    for k in('vn','ve','vd'):
        print(' ',k,'values',sorted(set(s[k] for s in S))[:14])
    # re-integrate logged v, compare with logged tracker
    worst=0
    for i in range(len(r['legs'])):
        L=[x for x in S if x['leg']==i];N,E,U=L[0]['n'],L[0]['e'],L[0]['u']
        for a,b in zip(L,L[1:]):
            dt=(b['t']-a['t'])/1000;N+=a['vn']*dt;E+=a['ve']*dt;U-=a['vd']*dt
        worst=max(worst,math.dist((N,E,U),(L[-1]['n'],L[-1]['e'],L[-1]['u'])))
    print('  logged tracker vs my own per-leg reintegration: worst %.2f m'%worst)
    # height vs down sensor
    ts=[s['t'] for s in S];rows=[]
    for t,kv in r['obs']:
        d=int(kv['down_mm'])/1000
        i=bisect.bisect(ts,t)-1
        if 0<=i and t-ts[i]<=300 and 0<d<30: rows.append((t,S[i]['u'],d,S[i]['leg']))
    diff=[a-b for _,a,b,_ in rows]
    print('  u-down: n %d mean %+.3f sd %.3f min %+.2f max %+.2f corr(u,down) %s'%(len(diff),st.mean(diff),st.pstdev(diff),min(diff),max(diff),(round(st.correlation([x[1] for x in rows],[x[2] for x in rows]),3) if len(set(x[1] for x in rows))>1 else 'n/a')))
    print('  down range %.2f..%.2f  u range %.2f..%.2f'%(min(x[2] for x in rows),max(x[2] for x in rows),min(x[1] for x in rows),max(x[1] for x in rows)))
    print('  biggest single-sample jump in down between readings: %.2f m'%max(abs(a[2]-b[2]) for a,b in zip(rows,rows[1:])))
    # per leg
    T=[(float(k['n']),float(k['e']),float(k['u'])) for _,_,k in r['legs']]
    for i in range(len(T)):
        s=[x for x in S if x['leg']==i]
        R=[x for x in rows if x[3]==i]
        e=[seg((x['n'],x['e'],x['u']),T[i-1],T[i]) for x in s] if 0<i<6 else []
        v=[math.hypot(x['vn'],x['ve']) for x in s]
        print('  leg%d steps %3d dur %5.1fs endDistLog %.2f vhmax %.2f vdrange %+.1f..%+.1f stuckZero %2.0f%% dUlog %+.2f dDown %+.2f %s'%(i,len(s),(s[-1]['t']-s[0]['t'])/1000,s[-1]['dist'],max(v),min(x['vd'] for x in s),max(x['vd'] for x in s),100*sum(1 for x in s if x['vn']==x['ve']==x['vd']==0)/len(s),s[-1]['u']-s[0]['u'],(R[-1][2]-R[0][2]) if len(R)>1 else float('nan'),('line mean %.1f max %.1f cm'%(st.mean(e)*100,max(e)*100)) if e else ''))
    # stick-> response, using v 0.4 s after stick, steady phases
    b=collections.defaultdict(list)
    for s in S:
        j=bisect.bisect(ts,s['t']+500)
        if j<len(S):
            b[min(abs(s['stick_up'])//10*10,80)].append(abs(S[j]['vd']))
    print('  |stick_up| bin -> |vd| reported mean:',{k:round(st.mean(v),2) for k,v in sorted(b.items())})
    b=collections.defaultdict(list)
    for s in S:
        j=bisect.bisect(ts,s['t']+500)
        if j<len(S):
            st_=math.hypot(s['stick_fwd'],s['stick_right'])
            b[int(st_//5*5)].append(math.hypot(S[j]['vn'],S[j]['ve']))
    print('  horiz stick bin -> |vh| reported mean:',{k:round(st.mean(v),2) for k,v in sorted(b.items())})
    # hidden motion: intervals where reported vd==0 and down changes
    zs=[(a,b_) for a,b_ in zip(rows,rows[1:])]
    z=[(b_[2]-a[2])/((b_[0]-a[0])/1000) for a,b_ in zs if a[1]==b_[1]]
    if z: print('  where logged u frozen: down rate mean %+.3f m/s, abs mean %.3f, n=%d'%(st.mean(z),st.mean(abs(x) for x in z),len(z)))
