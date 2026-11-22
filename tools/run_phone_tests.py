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
lines=[];current='';launched=False
for line in process.stdout:
 print(line,end='',flush=True);lines.append(line)
 if line.startswith('INSTRUMENTATION_STATUS: class='):current=line
 if line.strip()=='INSTRUMENTATION_STATUS_CODE: 1' and 'ExhibitionFlowTest' in current and not launched:
  # MIUI blocks ActivityScenario's background start after instrumentation restarts the app.
  # Foreground its exact test activity after the scenario begins; never launch a second app UI.
  launched=True
  def foreground():
   subprocess.run(base+['shell','am','start','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n','com.example.vescurus/androidx.activity.ComponentActivity'],capture_output=True)
  threading.Timer(1.5,foreground).start()
process.wait();output=''.join(lines)
folder=Path('.local/device')/args.device;folder.mkdir(parents=True,exist_ok=True);(folder/'instrumentation.log').write_text(output,encoding='utf8')
raise SystemExit(0 if 'OK (4 tests)' in output and 'FAILURES!!!' not in output else 1)
