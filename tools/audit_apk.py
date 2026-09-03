"""Check final APK for accidentally bundled credentials and private build files."""
import hashlib,re,sys,zipfile,json
from pathlib import Path
patterns=[rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',rb'ghp_[A-Za-z0-9]{36}',rb'github_pat_[A-Za-z0-9_]{40,}',rb'AKIA[A-Z0-9]{16}',rb'https://(?:discord(?:app)?\.com)/api/webhooks/\d+/[A-Za-z0-9_-]+']
path=Path(sys.argv[1]);issues=[]
with zipfile.ZipFile(path) as z:
    for item in z.infolist():
        if item.is_dir():continue
        if item.filename.endswith(('.jks','.keystore','.env','.log','.swift')) or '/.git/' in item.filename:issues.append('Unexpected private/build file')
        data=z.read(item)
        if any(re.search(pattern,data) for pattern in patterns):issues.append('Credential pattern in '+item.filename)
        if re.search(rb'(?:C:\\\\Users\\\\|/Users/)[A-Za-z0-9_-]+',data):issues.append('Personal build path in '+item.filename)
if issues:raise SystemExit('\n'.join(sorted(set(issues))))
print(json.dumps({'result':'PASS','sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'checks':['credential patterns','private bundled files','personal build paths']}))
