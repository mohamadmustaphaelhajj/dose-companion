"""Pair the isolated Lab app with the Mac USB bridge, without copying the OpenAI API key."""
from pathlib import Path
import os
import shutil
import subprocess
from xml.sax.saxutils import escape
from bridge import config

settings=config()
if len(settings.get('BRIDGE_TOKEN',''))<32:
    raise SystemExit('Run python3 server/configure.py first.')
adb=shutil.which('adb')
if not adb:
    root=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))
    adb=str(root/'platform-tools/adb')
result=subprocess.run([adb,'devices'],capture_output=True,text=True,check=True)
devices=[line.split()[0] for line in result.stdout.splitlines()[1:] if len(line.split())>=2 and line.split()[1]=='device']
if len(devices)!=1:
    raise SystemExit('Connect and unlock exactly one Android phone, and allow USB debugging.')
base=[adb,'-s',devices[0]];package='com.dosecompanion.app.localexperiment'
def run(args,**kwargs):
    return subprocess.run(base+args,check=True,capture_output=True,**kwargs)
# Stop only the separate research app before changing its private pairing preferences.
run(['shell','am','force-stop',package]);run(['reverse','tcp:8787','tcp:8787'])
run(['shell','run-as',package,'mkdir','-p','shared_prefs'])
xml=('<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><string name="endpoint">http://127.0.0.1:8787</string><string name="token">'+escape(settings['BRIDGE_TOKEN'])+'</string></map>').encode()
run(['shell',"run-as "+package+" sh -c 'cat > shared_prefs/cloud_bridge.xml'"],input=xml)
run(['shell','run-as',package,'chmod','600','shared_prefs/cloud_bridge.xml'])
run(['shell','am','start','-n',package+'/com.dosecompanion.app.MainActivity'])
print('Dose Companion Lab paired and opened. No OpenAI key copied to the phone.')
