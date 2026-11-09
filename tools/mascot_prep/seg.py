from PIL import Image
import numpy as np, pickle
from scipy import ndimage as ndi
CH=['pip','nova','orbit','bolt']
def cells(n):
    a=np.array(Image.open(f'src/{n}.png').convert('RGBA')).astype(np.float32)
    m=a[...,3]>200
    lab,k=ndi.label(m); sl=ndi.find_objects(lab)
    items=[(i+1,sl[i]) for i in range(k)]
    items.sort(key=lambda t:(int((t[1][0].start+t[1][0].stop)/2//418),(t[1][1].start+t[1][1].stop)/2))
    assert len(items)==7
    out=[]
    for lid,s in items:
        y0,y1,x0,x1=s[0].start-12,s[0].stop+12,s[1].start-12,s[1].stop+12
        sub=a[y0:y1,x0:x1]; mk=(lab[y0:y1,x0:x1]==lid)
        mk=ndi.binary_fill_holes(mk)
        # colour decontamination: take interior colour, extend outward by nearest interior pixel
        core=ndi.binary_erosion(mk,iterations=2)
        idx=ndi.distance_transform_edt(~core,return_distances=False,return_indices=True)
        rgb=sub[...,:3][idx[0],idx[1]]
        soft=ndi.gaussian_filter(mk.astype(np.float32),0.9)
        out.append((rgb,soft,mk,(y0,x0)))
    return out
if __name__=='__main__':
    D={n:cells(n) for n in CH}
    pickle.dump(D,open('cells.pkl','wb'))
    for n in CH:
        print(n,[ (c[2].sum(),c[0].shape[:2]) for c in D[n]][:2])
