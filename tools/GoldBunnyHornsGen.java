import java.awt.Color; import java.awt.Graphics2D; import java.awt.image.BufferedImage;
import java.io.File; import java.util.Random; import javax.imageio.ImageIO;
/** Gold horns for the gold bunny: 32x32, matching the vanilla attachment's UV size. */
public class Horns { public static void main(String[] a) throws Exception {
  int S=32; BufferedImage img=new BufferedImage(S,S,BufferedImage.TYPE_INT_ARGB);
  Graphics2D g=img.createGraphics(); Random rnd=new Random(20260822L);
  Color light=new Color(0xFF,0xDC,0x8A), gold=new Color(0xE8,0xB0,0x3C),
        deep=new Color(0xB5,0x7C,0x1E), shadow=new Color(0x7A,0x51,0x12);
  for(int y=0;y<S;y++) for(int x=0;x<S;x++){
    float t=(float)y/(S-1);
    Color c = t<0.5f ? mix(light,gold,t*2f) : mix(gold,deep,(t-0.5f)*2f);
    if((y%3)==0) c=mix(c,light,0.22f); else if((y%5)==0) c=mix(c,shadow,0.20f);
    if(rnd.nextInt(10)==0) c=mix(c,light,0.25f);
    g.setColor(c); g.fillRect(x,y,1,1);
  }
  g.dispose();
  File out=new File(a[0],"Common/NPC/Supporter/Gold_Bunny_Horns.png");
  out.getParentFile().mkdirs(); ImageIO.write(img,"png",out);
  System.out.println("wrote "+out+" (32x32)");
 }
 static Color mix(Color a,Color b,float t){ float u=Math.max(0,Math.min(1,t));
  return new Color(Math.round(a.getRed()+(b.getRed()-a.getRed())*u),
   Math.round(a.getGreen()+(b.getGreen()-a.getGreen())*u),
   Math.round(a.getBlue()+(b.getBlue()-a.getBlue())*u)); } }
