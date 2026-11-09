import numpy as np, pickle, os
from PIL import Image
from compose import render, extents, NAMES
from feet import body_mask
O=pickle.load(open('composed2.pkl','rb'))
OUT='out'
os.makedirs(OUT,exist_ok=True)
rep={}
for n,(F,ax,ay,_) in O.items():
    e=extents(F,ax,ay)
    L=max(max(x[0],x[1]) for x in e); T=max(x[2] for x in e); B=max(x[3] for x in e)
    bw=body_mask(F[0][1]>.5).any(0).sum()
    rep[n]=dict(L=L,T=T,B=B,bodyw=int(bw))
    print(n,rep[n])
# per-character scale (each fills the safe box); body-size spread printed above
for n,(F,ax,ay,_) in O.items():
    r=rep[n]
    s=min(66.5/r["L"], 133.0/r["T"])
    s=np.floor(s*10000)/10000
    d=f'{OUT}/{n}'; os.makedirs(d,exist_ok=True)
    mins=[]
    def eyec(R,A):
        r,g,b=R[...,0],R[...,1],R[...,2]
        m=(b>r+70)&(b>g+20)&(A>.9); y,x=np.where(m); return np.array([x.mean(),y.mean()])
    e0=eyec(*F[0])
    for i,(nm,(R,A)) in enumerate(zip(NAMES,F)):
        dx=dy=0.0
        if i>=3:
            dd=np.zeros(2)  # body-only registration (face split tried, inconclusive)
            print('   ',n,nm,'face/body split shift (src px)',np.round(dd,2))
        A=A.copy()
        if i<6: A[int(round(ay)):]=0          # nothing below the shared baseline
        img=render(R,A,s,ax-dx,ay-dy,144,72.0,138.0)
        Image.fromarray(img,'RGBA').save(f'{d}/{nm}.png')
    # thumb: same layout at 4/3
    R,A=F[0]; Image.fromarray(render(R,A,s*192/144,ax,ay,192,96.0,138.0*192/144),'RGBA').save(f'{d}/thumb.png')
    print(n,'s',s,'body px at 144:',round(rep[n]['bodyw']*s))
