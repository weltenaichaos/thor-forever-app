import sys, math
# Draws the Thor Forever pictures for the WoW shortcut: the library cover (720x1000,
# app/src/main/res/drawable-nodpi/thor_cover.png) and the loading screen banner
# (1920x1080, thor_banner.png).
# Usage: python3 tools/make_thor_cover.py app/src/main/res/font/cinzel_bold.ttf out.png [width height]
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops
font_path = sys.argv[1]; out = sys.argv[2]
S = 3                      # draw 3x larger, then shrink (smooth edges, like the old screen)
W, H = (int(sys.argv[3]), int(sys.argv[4])) if len(sys.argv) > 4 else (720, 1000)
w, h = W*S, H*S
u = 60*S if H >= W else (H*S)//12   # design unit
def grad(size, top, bottom, mid=None):
    g = Image.new("RGB", (1, size[1]))
    for y in range(size[1]):
        f = y/(size[1]-1)
        if mid:
            a, b, t = (top, mid, f*2) if f < .5 else (mid, bottom, f*2-1)
        else:
            a, b, t = top, bottom, f
        g.putpixel((0, y), tuple(int(a[i]+(b[i]-a[i])*t) for i in range(3)))
    return g.resize(size)
img = grad((w, h), (65,110,151), (16,38,59))
# keep the lower part the deep blue
deep = Image.new("RGB", (w, h), (16,38,59))
mask = grad((w, h), (0,0,0), (255,255,255)).convert("L").point(lambda v: min(255, max(0, (v-90)*2)))
img = Image.composite(deep, img, mask)
d = ImageDraw.Draw(img)

def word(text, size, spacing, cx, cy, metal):
    f = ImageFont.truetype(font_path, size)
    # letter spacing: draw char by char
    widths = [d.textlength(c, font=f) for c in text]
    total = sum(widths) + spacing*(len(text)-1)
    asc, desc = f.getmetrics()
    m = Image.new("L", (w, h), 0); md = ImageDraw.Draw(m)
    x = cx - total/2; y = cy - (asc+desc)/2
    for c, cw in zip(text, widths):
        md.text((x, y), c, font=f, fill=255); x += cw + spacing
    def grown(r, dy=0):
        g = m.filter(ImageFilter.MaxFilter(2*r+1)) if r > 0 else m
        return ImageChops.offset(g, 0, dy)
    px = u/45
    img.paste((10,22,34), mask=grown(int(3*px)|1, int(2*px)))
    img.paste((138,102,52), mask=grown(int(2.5*px)|1))
    img.paste((42,26,12), mask=grown(int(1.5*px)|1))
    bbox = m.getbbox()
    top, bot = bbox[1], bbox[3]
    g = Image.new("RGB", (w, h), metal[0])
    gs = grad((w, bot-top), metal[0], metal[2], metal[1])
    g.paste(gs, (0, top))
    img.paste(g, mask=m)
    return total, y + asc

gold = [(255,253,242),(241,228,194),(176,140,84)]
white = [(255,255,255),(244,242,236),(184,180,170)]
cy0 = int(h*0.30) if H >= W else int(h*0.27)
word("WORLD OF WARCRAFT", int(u*0.74), u//16, w//2, cy0, gold)
word("FOREVER", int(u*1.55), u//8, w//2, cy0 + int(u*1.45), white)
f = ImageFont.truetype(font_path, int(u*0.5))
t = "on the AYN Thor"; tw = d.textlength(t, font=f)
ty = cy0 + int(u*2.45)
d.text((w/2-tw/2+S, ty+S), t, font=f, fill=(10,22,34))
d.text((w/2-tw/2, ty), t, font=f, fill=(169,207,231))
# infinity swash
cx, cy, hw, th = w/2, cy0 + int(u*3.6), u*1.2, u/22
for pas, col in enumerate([(8,22,34),(214,210,200),(255,255,255)]):
    grow = 1.5*S if pas == 0 else 0; shrink = .35 if pas == 2 else 1; lift = .35 if pas == 2 else 0
    for i in range(2400):
        a = 2*math.pi*i/2400; s, c = math.sin(a), math.cos(a); dd = 1+s*s
        x = cx + hw*c/dd; y = cy + hw*1.1*s*c/dd; r = th*(.3+.7*abs(c))
        rr = r*shrink+grow; y -= r*lift
        d.ellipse((x-rr, y-rr, x+rr, y+rr), fill=col)
# tails
for side in (-1, 1):
    for col, extra in (((8,22,34), .7*S), ((226,222,212), 0)):
        pts_top, pts_bot = [], []
        start, length, r0 = hw + u/10, u*2.4, th*1.2
        for i in range(61):
            f_ = i/60; r = r0*(1-f_)**1.4 + extra*(1-f_); x = cx + side*(start + f_*length)
            pts_top.append((x, cy-r)); pts_bot.append((x, cy+r))
        d.polygon(pts_top + pts_bot[::-1], fill=col)
# frame: gold, dark bronze, then sky blue 4 px in
for inset, col in ((0,(201,168,106)), (1*S,(74,52,24)), (4*S,(131,176,206))):
    d.rectangle((inset, inset, w-1-inset, h-1-inset), outline=col, width=S)
img.resize((W, H), Image.LANCZOS).save(out)
