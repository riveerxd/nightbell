import sys, numpy as np, cv2
from PIL import Image
out=sys.argv[1]; W,H=1440,3200
rng=np.random.default_rng(7)
yy,xx=np.mgrid[0:H,0:W].astype(np.float32)
def save(name,img):
    img=np.clip(img,0,255).astype(np.uint8); Image.fromarray(img).save(f"{out}/{name}.png")
def grain(a,s=3): return a+rng.normal(0,s,a.shape[:2])[...,None]
def lerp(c1,c2,t): return np.array(c1)[None,None,:]*(1-t[...,None])+np.array(c2)[None,None,:]*t[...,None]
# 1 ink: near black, faint grain
save("1-ink", grain(np.zeros((H,W,3))+[11,13,18],2.5))
# 2 night: navy at the top falling to black
t=(yy/H)**0.8; save("2-night", grain(lerp([30,40,64],[6,7,11],t)))
# 3 contours: thin topographic lines on charcoal
f=np.zeros((H,W),np.float32)
for _ in range(9):
    cx,cy=rng.uniform(0,W),rng.uniform(0,H); r=rng.uniform(500,1400)
    f+=np.exp(-((xx-cx)**2+(yy-cy)**2)/(2*r*r))*rng.uniform(.6,1.2)
f=cv2.GaussianBlur(f,(0,0),20); lines=np.abs(((f*14)%1)-.5)<.035
base=np.zeros((H,W,3))+[14,16,21]; base[lines]=[52,57,68]; save("3-contours", grain(cv2.GaussianBlur(base,(0,0),1.1),2))
# 4 stars: sparse points on blue black
base=lerp([14,18,32],[5,6,10],yy/H)
for _ in range(900):
    x,y=int(rng.uniform(0,W)),int(rng.uniform(0,H)); b=rng.uniform(60,230); r=rng.choice([1,1,1,2,2,3])
    cv2.circle(base,(x,y),r,(b,b,min(255,b+15)),-1,cv2.LINE_AA)
save("4-stars", base)
# 5 ridges: layered mountain silhouettes at night
img=lerp([24,30,48],[10,12,20],(yy/H)); 
for i,(h0,c) in enumerate([(0.52,[20,25,40]),(0.6,[15,19,31]),(0.69,[11,14,23]),(0.79,[7,9,15])]):
    x=np.arange(W); n=np.zeros(W)
    for k in range(1,7): n+=np.sin(x/W*np.pi*(k*1.7)+rng.uniform(0,6))*rng.uniform(20,90)/k
    edge=(h0*H+n).astype(int)
    m=yy>=edge[None,:]; img[m]=c
save("5-ridges", grain(img,2))
# 6 dunes: soft layered curves in greys
img=np.zeros((H,W,3))+[18,19,23]
for i in range(7):
    edge=(H*(0.35+i*0.09)+np.sin(xx[0]/W*np.pi*2.2+i)*120*(1+i*0.15)).astype(int)
    m=yy>=edge[None,:]; v=18+i*7; img[m]=[v,v+1,v+5]
save("6-dunes", grain(cv2.GaussianBlur(img,(0,0),2),2))
# 7 dots: dot grid on charcoal
img=np.zeros((H,W,3))+[13,14,18]
for y in range(40,H,56):
    for x in range(40,W,56): cv2.circle(img,(x,y),3,(44,47,56),-1,cv2.LINE_AA)
save("7-dots", img)
# 8 cobalt: deep brand blue with a vignette
d=np.sqrt(((xx-W/2)/W)**2+((yy-H*0.35)/H)**2); save("8-cobalt", grain(lerp([26,48,120],[7,10,24],np.clip(d*1.6,0,1)),2.5))
