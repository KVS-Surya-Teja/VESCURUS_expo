"""Run VESCURUS tests on an unlocked physical phone without erasing user data."""
import argparse,re,subprocess,threading
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--device',required=True);args=p.parse_args()
base=['adb','-s',args.device]
subprocess.run(base+['shell','input','keyevent','KEYCODE_WAKEUP'],check=True)
trust=subprocess.check_output(base+['shell','dumpsys','trust'],text=True,encoding='utf8',errors='replace')
current=re.search(r'User .*?\(current\):(.*?)(?=\n\s*User |\Z)',trust,re.S)
if current and 'deviceLocked=1' in current.group(1):
 raise SystemExit('Unlock the phone before running UI instrumentation; no tests were started.')
process=subprocess.Popen(base+['shell','am','instrument','-w','-r','com.example.vescurus.test/androidx.test.runner.AndroidJUnitRunner'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf8',errors='replace')
lines=[];bundle={};active_test=None;pending=None;generation=0
foreground_lock=threading.Lock()

def foreground(token,test_key):
 # MIUI may block ActivityScenario's background start. Scope this workaround to
 # the still-running scenario; a completed or replaced scenario must not launch it.
 with foreground_lock:
  if token!=generation or active_test!=test_key or process.poll() is not None:return
  try:
   subprocess.run(base+['shell','am','start','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n','com.example.vescurus/androidx.activity.ComponentActivity'],capture_output=True,timeout=5)
  except (subprocess.SubprocessError,OSError):pass

def change_test(test_key,expected=None):
 global active_test,pending,generation
 with foreground_lock:
  if expected is not None and active_test!=expected:return
  if active_test==test_key:return
  generation+=1
  if pending is not None:pending.cancel()
  pending=None;active_test=test_key
  if test_key is not None and 'ExhibitionFlowTest' in test_key[0]:
   pending=threading.Timer(1.5,foreground,args=(generation,test_key))
   pending.daemon=True
   pending.start()

try:
 for line in process.stdout:
  print(line,end='',flush=True);lines.append(line)
  if line.startswith('INSTRUMENTATION_STATUS: '):
   key,separator,value=line[len('INSTRUMENTATION_STATUS: '):].rstrip('\r\n').partition('=')
   if separator and key in ('class','test'):bundle[key]=value
  elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
   code=int(line.partition(':')[2].strip())
   if code==1 and 'class' in bundle and 'test' in bundle:
    change_test((bundle['class'],bundle['test']))
   elif code<=0:
    with foreground_lock:current_test=active_test
    if current_test is not None:
     completed=(bundle.get('class',current_test[0]),bundle.get('test',current_test[1]))
     change_test(None,expected=completed)
   bundle={}
 process.wait()
finally:
 change_test(None)
output=''.join(lines)
folder=Path('.local/device')/args.device;folder.mkdir(parents=True,exist_ok=True);(folder/'instrumentation.log').write_text(output,encoding='utf8')
summary=re.search(r'OK \((\d+) tests?\)',output)
raise SystemExit(0 if process.returncode==0 and summary and int(summary.group(1))>0 and 'FAILURES!!!' not in output and 'INSTRUMENTATION_FAILED' not in output else 1)
