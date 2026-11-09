import numpy as np, pickle
from scipy import ndimage as ndi
A=pickle.load(open('aligned.pkl','rb'))
def eyezone(n,i):
    I=A[n][0]; F=A[n][i]
    r,g,b=[I[0][...,k] for k in range(3)]
    blue=(b>r+70)&(b>g+20)&(I[1]>.9)
    ys,xs=np.where(blue); cy=ys.mean(); cx=xs.mean()
    d=(np.abs(F[0]-I[0]).sum(-1)>45)&(I[1]>.98)&(F[1]>.98)
    d[:int(cy-95)]=0   # above brows (antenna)
    d[int(cy+80):]=0
    dd=ndi.binary_dilation(d,iterations=4)
    lab,k=ndi.label(dd)
    keep=np.zeros_like(d)
    info=[]
    for l in range(1,k+1):
        m=lab==l; yy,xx=np.where(m)
        if m.sum()<150: continue
        info.append((l,int(m.sum()),round(yy.mean()-cy),round(xx.mean()-cx)))
        if yy.mean()<cy+40: keep|=m
    return cy,cx,keep,info
if __name__=='__main__':
    for n in A:
        for i in (1,2):
            cy,cx,z,info=eyezone(n,i); print(n,i,'eye c',round(cy),round(cx),info)
