import numpy as np, pickle, cv2
from scipy import ndimage as ndi
from feet import body_mask
O=pickle.load(open('composed.pkl','rb'))
GAP={'pip':3,'orbit':3,'bolt':3,'nova':None}
def fix(n):
    F,ax,ay,s=O[n]; R,A=F[6]; m=A>.5
    body=body_mask(m); yb=np.where(body)[0].max()
    cand=m&~ndi.binary_dilation(body,iterations=1); cand[:int(yb-45)]=0
    cand=ndi.binary_opening(cand,iterations=2)
    lab,k=ndi.label(cand); sz=ndi.sum(cand,lab,range(1,k+1))
    feet=np.isin(lab,[i+1 for i,v in enumerate(sz) if v>800])
    bottom=np.where(feet)[0].max()+1
    gap=GAP[n]
    if gap is None: return F[6],0
    d=int(round(bottom-(ay-gap)))
    if d<=0: return F[6],0
    body_d=ndi.binary_dilation(body,iterations=2)
    fz=ndi.binary_dilation(feet,iterations=3)&~body_d
    Ab=np.where(ndi.binary_dilation(feet,iterations=5)&~body_d,0,A)
    Af=A*fz
    Af=np.roll(Af,-d,0); Rf=np.roll(R,-d,0)
    Af[-d:]=0
    # feather top cut a bit
    Af=np.clip(ndi.gaussian_filter(Af,1.0)*1.0,0,1)*(np.roll(fz,-d,0)|True)
    Ao=Af+Ab*(1-Af)
    Ro=(Rf*Af[...,None]+R*(Ab*(1-Af))[...,None])/np.maximum(Ao,1e-6)[...,None]
    return (Ro,Ao),d
if __name__=='__main__':
    for n in O:
        (R,A),d=fix(n); print(n,'lifted',d)
        O[n][0][6]=(R,A)
    pickle.dump(O,open('composed2.pkl','wb'))
