import numpy as np, pickle
from reg import D,canvas,warp,P
from PIL import Image
R=pickle.load(open('reg.pkl','rb'))
def aligned(n):
    out=[]
    for i,c in enumerate(D[n]):
        Rr,A=canvas(c)
        s,dx,dy=R[n][i]
        Rw,Aw=warp(Rr,A,s,dx,dy)
        out.append((Rw,Aw))
    return out
if __name__=='__main__':
    A={n:aligned(n) for n in D}
    pickle.dump(A,open('aligned.pkl','wb'))
    for n in D:
        I=A[n][0]
        for i in (1,2):
            d=np.abs(A[n][i][0]-I[0]).sum(-1)*(I[1]>.98)*(A[n][i][1]>.98)
            ys,xs=np.where(d>40)
            print(n,i,'diff bbox y',ys.min(),ys.max(),'x',xs.min(),xs.max(),'n',len(ys))
            Image.fromarray(np.clip(d*2,0,255).astype(np.uint8)).save(f'dbg_diff_{n}_{i}.png')
