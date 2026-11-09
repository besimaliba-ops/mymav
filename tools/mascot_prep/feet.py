import numpy as np, pickle
from scipy import ndimage as ndi
O=pickle.load(open('composed.pkl','rb'))
def body_mask(m,rad=95):
    import cv2
    k=cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(2*rad+1,2*rad+1))
    return cv2.morphologyEx(m.astype(np.uint8),cv2.MORPH_OPEN,k).astype(bool)
def lift_feet(F,ax,ay,target_gap):
    R,Aa=F[6]; m=Aa>.5
    body=body_mask(m)
    ys,xs=np.where(body); yb=ys.max()   # body bottom
    cand=m&~ndi.binary_dilation(body,iterations=1)
    cand[:int(yb-45)]=0
    cand=ndi.binary_opening(cand,iterations=2)
    lab,k=ndi.label(cand)
    sz=ndi.sum(cand,lab,range(1,k+1))
    keepl=[i+1 for i,s in enumerate(sz) if s>800]
    feet=np.isin(lab,keepl)
    feet=ndi.binary_dilation(feet,iterations=3)&m&~ndi.binary_erosion(body,iterations=0)
    fy,fx=np.where(feet); bottom=fy.max()+1
    d=int(round(bottom-(ay-target_gap)))
    return feet,d,len(keepl),yb
if __name__=='__main__':
    for n,(F,ax,ay,s) in O.items():
        feet,d,k,yb=lift_feet(F,ax,ay,8)
        print(n,'feet comps',k,'shift up',d,'body bottom',yb,'ay',ay)
