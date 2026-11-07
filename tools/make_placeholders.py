"""Generates the bundled placeholder 64x64 skins. Run: python3 tools/make_placeholders.py"""
from PIL import Image
import os
skins = {
 "commando": ((60,90,60),(200,170,130),(40,40,40)),
 "scout":    ((70,140,70),(230,190,150),(90,60,30)),
 "ranger":   ((50,120,220),(220,180,140),(30,30,80)),
 "mystic":   ((140,60,200),(210,170,150),(60,20,90)),
 "blaze":    ((240,120,20),(230,190,150),(120,30,10)),
 "frost":    ((120,210,240),(240,220,200),(40,90,140)),
 "shadow":   ((50,50,60),(190,160,140),(15,15,20)),
 "legend":   ((40,220,210),(240,200,170),(20,90,100)),
}
def box(img, x0,y0,x1,y1,col):
    for x in range(x0,x1):
        for y in range(y0,y1): img.putpixel((x,y),col+(255,))
out="src/main/resources/assets/fortnitelocker/textures/skins"
os.makedirs(out,exist_ok=True)
for name,(body,skin,legs) in skins.items():
    im=Image.new("RGBA",(64,64),(0,0,0,0))
    box(im,0,0,32,16,skin)            # head
    box(im,8,8,16,16,skin)            # face
    box(im,8,8,16,10,legs)            # hair/hat band
    im.putpixel((10,12),(255,255,255,255)); im.putpixel((13,12),(255,255,255,255))
    im.putpixel((10,12),(20,20,20,255)); im.putpixel((13,12),(20,20,20,255))
    box(im,16,16,40,32,body)          # torso
    box(im,40,16,56,32,body)          # right arm
    box(im,0,16,16,32,legs)           # right leg
    box(im,16,48,32,64,legs)          # left leg
    box(im,32,48,48,64,body)          # left arm
    im.save(f"{out}/placeholder_{name}.png")
