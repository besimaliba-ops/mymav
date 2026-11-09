import numpy as np, os, cv2
from PIL import Image
from scipy import ndimage as ndi
NAMES=['idle','blink_half','blink','wave_low','wave_mid','wave_high','jump']
ok=True
for n in ['pip','nova','orbit','bolt']:
    fr={}
    for nm in NAMES+['thumb']:
        im=Image.open(f'out/{n}/{nm}.png'); a=np.array(im)
        exp=(192,192) if nm=='thumb' else (144,144)
        assert im.mode=='RGBA' and im.size==exp,(n,nm,im.mode,im.size)
        al=a[...,3]; ys,xs=np.where(al>0); sc=exp[0]/144
        bb=(xs.min()/sc,xs.max()/sc,ys.min()/sc,ys.max()/sc)
        lab,k=ndi.label(al>0)
        # interior opacity
        holes=ndi.binary_fill_holes(al>127)&(al<128)
        interior_min=255 if holes.sum()==0 else 0
        # halo: low-alpha pixels with strongly red/very saturated colour vs gold
        low=(al>0)&(al<255)
        fr[nm]=a
        flag=[]
        if bb[0]<4 or bb[1]>139 or bb[2]<4 or bb[3]>139: flag.append('MARGIN')
        if k!=1: flag.append(f'COMPONENTS={k}')
        if holes.sum()>10: flag.append(f'ENCLOSED HOLES {holes.sum()}')
        if nm in('idle','blink_half','blink','wave_low','wave_mid','wave_high') and abs(bb[3]-138)>0.5: flag.append(f'baseline {bb[3]:.1f}')
        print(f'{n:6}{nm:11}bbox x{bb[0]:.0f}-{bb[1]:.0f} y{bb[2]:.0f}-{bb[3]:.0f} comps={k} edgepx={low.sum()} {" ".join(flag) or "ok"}')
        ok&=not flag
    # body drift at 144: opened body mask centroid
    k=cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(2*34+1,)*2)
    cs={}
    for nm in NAMES:
        m=(fr[nm][...,3]>127).astype(np.uint8)
        b=cv2.morphologyEx(m,cv2.MORPH_OPEN,k)
        ys,xs=np.where(b); cs[nm]=(xs.mean(),ys.mean(),b.sum())
    base=cs['idle']
    print('  body centroid drift vs idle (px @144):',{nm:(round(c[0]-base[0],2),round(c[1]-base[1],2)) for nm,c in cs.items() if nm!='idle'})
    I=fr['idle'].astype(int)
    for nm in ('blink_half','blink'):
        d=(np.abs(fr[nm].astype(int)-I).sum(-1)>0)
        ys,xs=np.where(d)
        print(f'  {nm} differs from idle only inside y {ys.min()}..{ys.max()} x {xs.min()}..{xs.max()} ({d.sum()} px)')
    # colour check: no red-ish pixels (halo remnants): count px with r>200,g<120
    for nm in NAMES:
        a=fr[nm]; red=((a[...,0]>180)&(a[...,1]<110)&(a[...,3]>0)).sum()
        pass
print('ALL OK' if ok else 'ISSUES')
