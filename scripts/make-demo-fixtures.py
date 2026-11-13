from pathlib import Path
from PIL import Image, ImageDraw
import math, random, subprocess
out=Path('outputs/fixtures');out.mkdir(parents=True,exist_ok=True)
names=['海边日落 · 旅行日记','山间公路 · 周末出行','城市夜色 · 随手记录','函数与图像 · 学习笔记','山野与风 · 慢慢走','蓝色时刻 · 海岸线']
palettes=[((243,173,137),(74,113,132)),((154,197,211),(57,98,98)),((42,49,86),(36,39,69)),((236,226,203),(179,177,143)),((163,185,152),(75,110,87)),((113,153,184),(52,87,126))]
for i,(name,(top,bot)) in enumerate(zip(names,palettes)):
 im=Image.new('RGB',(960,540));d=ImageDraw.Draw(im)
 for y in range(540):
  f=y/540;d.line((0,y,960,y),fill=tuple(round(a+(b-a)*f) for a,b in zip(top,bot)))
 if i in (0,5):
  d.ellipse((660,90,760,190),fill=(255,217,174) if i==0 else (216,227,231))
  for j in range(18):
   y=290+j*14;d.line([(0,y),(960,y-4)],fill=tuple(min(255,c+18) for c in bot),width=2)
  d.polygon([(0,540),(0,380),(190,410),(360,465),(640,540)],fill=(38,61,65))
  d.polygon([(0,540),(0,455),(180,482),(310,540)],fill=(209,174,143))
 elif i in (1,4):
  d.polygon([(0,360),(230,110),(450,355),(600,190),(960,390),(960,540),(0,540)],fill=(102,139,136))
  d.polygon([(0,420),(260,270),(490,410),(730,230),(960,440),(960,540),(0,540)],fill=bot)
  d.polygon([(350,540),(470,380),(490,380),(615,540)],fill=(68,77,77))
  d.line((480,390,485,520),fill=(231,211,163),width=5)
 elif i==2:
  random.seed(3)
  for x in range(0,960,85):
   y=random.randrange(120,320);d.rectangle((x,y,x+65,540),fill=(27,34,58))
   for yy in range(y+15,510,30):
    for xx in range(x+10,x+55,20):
     if random.random()>.3:d.rectangle((xx,yy,xx+7,yy+10),fill=(220,177,121))
 else:
  for x in range(60,920,40):d.line((x,40,x,500),fill=(214,207,184))
  for y in range(60,520,40):d.line((40,y,920,y),fill=(214,207,184))
  d.line((80,350,880,350),fill=(92,104,96),width=3);d.line((460,60,460,480),fill=(92,104,96),width=3)
  pts=[(x,350-int(170*math.sin((x-460)/120))) for x in range(80,880)]
  d.line(pts,fill=(164,83,96),width=7)
 im.save(out/f'{i}.png')
 subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-loop','1','-i',str(out/f'{i}.png'),'-f','lavfi','-i','sine=frequency=220:sample_rate=44100','-t','35','-vf','scale=640:360','-r','15','-c:v','libx264','-preset','ultrafast','-crf','28','-pix_fmt','yuv420p','-c:a','aac','-b:a','48k','-movflags','+faststart','-y',str(out/f'{name}.mp4')],check=True)
(out/'中文学习字幕.srt').write_text('1\n00:00:00,000 --> 00:00:15,000\n把喜欢的瞬间，留在映匣。\n\n2\n00:00:15,000 --> 00:00:34,000\n本地播放 · 中文字幕验证\n',encoding='utf-8')
