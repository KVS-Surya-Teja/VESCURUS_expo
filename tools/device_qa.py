"""Small ADB helper for repeatable VESCURUS-only manual QA. No private-app browsing."""
import argparse,json,re,subprocess,time,xml.etree.ElementTree as E
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('--device',required=True);p.add_argument('--capture');p.add_argument('--tap-text');p.add_argument('--tap-id');p.add_argument('--type');p.add_argument('--dump',action='store_true');p.add_argument('--scroll-up',action='store_true');p.add_argument('--scroll-down',action='store_true');a=p.parse_args()
def adb(*args):return subprocess.check_output(['adb','-s',a.device,*args])
if a.type is not None:
 text=a.type.replace(' ','%s')
 subprocess.run(['adb','-s',a.device,'shell','input','text',text],check=True)
if a.tap_text or a.tap_id or a.dump or a.scroll_up or a.scroll_down:
 for attempt in range(3):
  result=subprocess.run(['adb','-s',a.device,'shell','uiautomator','dump','/sdcard/vescurus-qa.xml'],capture_output=True)
  if result.returncode==0 and b'dumped to:' in result.stdout:break
  time.sleep(.5)
 else:raise SystemExit('Android could not provide a fresh UI hierarchy; no stale controls were used.')
 xml=adb('exec-out','cat','/sdcard/vescurus-qa.xml').decode('utf8')
 root=E.fromstring(xml)
 nodes=[n for n in root.iter('node') if n.attrib.get('package')=='com.example.vescurus']
 if a.dump:
  print(json.dumps([{k:n.attrib.get(k) for k in ['text','resource-id','content-desc','bounds']} for n in nodes if n.attrib.get('text') or n.attrib.get('resource-id')],ensure_ascii=True))
 if a.scroll_up or a.scroll_down:
  containers=[n for n in nodes if n.attrib.get('resource-id')=='cook-scroll' or n.attrib.get('scrollable')=='true']
  if not containers:raise SystemExit('No scrollable VESCURUS container observed.')
  x1,y1,x2,y2=map(int,re.findall(r'\d+',containers[0].attrib['bounds']))
  x=(x1+x2)//2;upper=y1+(y2-y1)//4;lower=y1+3*(y2-y1)//4
  subprocess.run(['adb','-s',a.device,'shell','input','swipe',str(x),str(lower if a.scroll_up else upper),str(x),str(upper if a.scroll_up else lower),'350'],check=True)
 if a.tap_text or a.tap_id:
  matches=[n for n in nodes if (a.tap_text and n.attrib.get('text')==a.tap_text) or (a.tap_id and n.attrib.get('resource-id')==a.tap_id)]
  if not matches:raise SystemExit('Requested VESCURUS control is not in the current UI; inspect before continuing.')
  n=matches[0];x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']))
  subprocess.run(['adb','-s',a.device,'shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)],check=True)
  print('Tapped',a.tap_id or a.tap_text)
if a.capture:
 resumed=adb('shell','dumpsys','activity','activities').decode('utf8',errors='replace')
 if not any('com.example.vescurus/' in line for line in resumed.splitlines() if 'topResumedActivity' in line or 'mResumedActivity' in line):raise SystemExit('VESCURUS is not foreground; screenshot skipped.')
 target=Path('.local/device')/a.device;target.mkdir(parents=True,exist_ok=True)
 name=re.sub('[^a-zA-Z0-9_-]','_',a.capture)
 (target/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
 print(str(target/(name+'.png')))
