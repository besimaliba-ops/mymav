import numpy as np, pickle
from scipy import ndimage as ndi, optimize
D=pickle.load(open('cells.pkl','rb'))
P=520
def canvas(c, off=(0,0)):
    rgb,soft,mk,_=c
    h,w=soft.shape
    R=np.zeros((P,P,3),np.float32);A=np.zeros((P,P),np.float32)
    y=(P-h)//2+off[0]; x=(P-w)//2+off[1]
    R[y:y+h,x:x+w]=rgb;A[y:y+h,x:x+w]=soft
    return R,A
def warp(R,A,s,dx,dy):
    # output(p)=input((p-c)/s + c - d) ; scale about canvas centre
    c=np.array([P/2,P/2])
    M=np.eye(2)/s
    off=c-M@c-np.array([dy,dx])/s
    Rw=np.stack([ndi.affine_transform(R[...,i],M,offset=off,order=1) for i in range(3)],-1)
    Aw=ndi.affine_transform(A,M,offset=off,order=1)
    return Rw,Aw
def lum(R): return R@np.array([.3,.59,.11],np.float32)
def cost(params,I,F,mask_ex=None):
    s,dx,dy=params
    Rw,Aw=warp(F[0],F[1],s,dx,dy)
    both=(Aw>.98)&(I[1]>.98)
    if both.sum()<2000: return 1e3
    d=np.abs(lum(Rw)-lum(I[0]))[both]
    k=int(len(d)*.55)
    return np.partition(d,k)[:k].mean()
def register(n):
    cs=D[n]; I=canvas(cs[0]); res=[(1,0,0)]
    for i in range(1,7):
        F=canvas(cs[i]); best=None
        for dx0 in range(-60,61,12):
          for dy0 in range(-60,61,12):
            v=cost((1,dx0,dy0),I,F)
            if best is None or v<best[0]: best=(v,dx0,dy0)
        r=optimize.minimize(cost,(1,best[1],best[2]),args=(I,F),method='Powell',options=dict(xtol=.05,ftol=1e-4))
        res.append(tuple(r.x)); print(n,i,'cost %.2f->%.2f'%(best[0],r.fun),'s=%.4f dx=%.1f dy=%.1f'%tuple(r.x),flush=True)
    return res
if __name__=='__main__':
    R={n:register(n) for n in D}
    pickle.dump(R,open('reg.pkl','wb'))
