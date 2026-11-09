import numpy as np, pickle, os, cv2
from scipy import ndimage as ndi
from blink import A, eyezone
CH=['pip','nova','orbit','bolt']
NAMES=['idle','blink_half','blink','wave_low','wave_mid','wave_high','jump']
def composite_blinks(n):
    F=[(f[0].copy(),f[1].copy()) for f in A[n]]
    I=F[0]
    zones={}
    for i in (1,2):
        cy,cx,z,info=eyezone(n,i)
        lab,k=ndi.label(z); keep=np.zeros_like(z)
        for l in range(1,k+1):
            m=lab==l; yy,xx=np.where(m)
            if abs(xx.mean()-cx)<=75 and -90<yy.mean()-cy<=10: keep|=m
        keep=ndi.binary_fill_holes(keep)
        w=np.clip(ndi.gaussian_filter(keep.astype(np.float32),2.0)*1.6,0,1)
        w=np.where(keep,1.0,w)*(I[1]>.5)
        R=I[0]*(1-w[...,None])+A[n][i][0]*w[...,None]
        F[i]=(R,I[1].copy())
        zones[i]=keep
    return F,zones
def anchors(F):
    I=F[0]; m=I[1]>.5
    ys,xs=np.where(m); ybot=ys.max()
    feet=m[ybot-18:ybot+1]; fx=np.where(feet)[1].mean()
    return fx, ybot+1.0
def extents(F,ax,ay):
    e=[]
    for R,Aa in F:
        ys,xs=np.where(Aa>.5)
        e.append((ax-xs.min(), xs.max()+1-ax, ay-ys.min(), ys.max()+1-ay))
    return e
def render(R,Aa,s,ax,ay,size,tx,ty):
    # map canvas (ax,ay)->(tx,ty) scale s
    pm=np.concatenate([R*Aa[...,None],Aa[...,None]],-1).astype(np.float32)
    pm=cv2.GaussianBlur(pm,(0,0),0.35/s)
    M=np.array([[s,0,tx-s*ax],[0,s,ty-s*ay]],np.float32)
    o=cv2.warpAffine(pm,M,(size,size),flags=cv2.INTER_AREA if False else cv2.INTER_LINEAR,borderValue=0)
    a=o[...,3]; rgb=np.where(a[...,None]>1e-4,o[...,:3]/np.maximum(a[...,None],1e-4),0)
    a8=np.clip(np.round(a*255),0,255)
    rgb8=np.clip(np.round(rgb),0,255)
    rgb8[a8<3]=0; a8[a8<3]=0
    return np.dstack([rgb8,a8]).astype(np.uint8)
if __name__=='__main__':
    OUT={}
    for n in CH:
        F,z=composite_blinks(n); ax,ay=anchors(F); e=extents(F,ax,ay)
        L=max(max(x[0],x[1]) for x in e); T=max(x[2] for x in e)
        sx=68/L; sy=134/T; s=min(sx,sy)
        print(n,'anchor',round(ax,1),round(ay,1),'max half-width',round(L),'max top',round(T),'s=%.4f'%s,'limit by','width' if sx<sy else 'height')
        for nm,x in zip(NAMES,e): print('   ',nm,[round(v) for v in x])
        OUT[n]=(F,ax,ay,s)
    pickle.dump(OUT,open('composed.pkl','wb'))
