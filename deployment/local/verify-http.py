"""Verify local pages, cookies, login and configuration reads; no paid API calls."""
import http.cookiejar
import json
from pathlib import Path
import re
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent
STATE = ROOT/'target'
BASE = 'http://127.0.0.1:18090'
credentials = json.loads((STATE/'.env.runtime.json').read_text(encoding='utf-8'))
cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(cookies))
checks = []

def call(path, data=None, csrf=None, binary=False, base=BASE, method=None):
    headers = {'Content-Type':'application/json'} if data is not None else {}
    if csrf:
        headers['X-CSRF-TOKEN'] = csrf
    req = urllib.request.Request(base+path, data=json.dumps(data).encode() if data is not None else None, headers=headers,method=method)
    try:
        response = client.open(req,timeout=15)
    except urllib.error.HTTPError as exc:
        raise RuntimeError('HTTP '+str(exc.code)+' for local '+path) from None
    with response:
        payload = response.read()
        assert response.status==200, path
        if binary:
            return payload
        body = payload.decode()
        return json.loads(body) if 'json' in response.headers.get('Content-Type','') else body

for path in ('/','/admin/login','/api/login','/promoter/overview'):
    html = call(path)
    for asset in re.findall(r'(?:src|href)="(/assets/[^\"]+)"',html):
        call(asset,binary=True)
    checks.append('page and assets: '+path)
call('/',base='http://127.0.0.1:18092')
checks.append('partner frontend')
session = call('/admin/api/v1/auth/session')
assert not session['authenticated']
call('/admin/api/v1/auth/admin/login',{
    'username':credentials['IAM_BOOTSTRAP_ADMIN_USERNAME'],
    'password':credentials['IAM_BOOTSTRAP_ADMIN_PASSWORD']},session['csrfToken'])
session = call('/admin/api/v1/auth/session')
assert session['authenticated'] and 'admin' in {r.lower() for r in session['roles']}
checks.append('administrator password login through Nginx and Nacos')
for path in ('/admin/api/v1/admin/users','/admin/api/v1/admin/admins',
             '/admin/api/v1/admin/media/provider-status','/admin/api/v1/admin/media/mps-configuration',
             '/admin/api/v1/admin/billing/media-prices','/admin/api/v1/admin/operations/users'):
    call(path)
    checks.append('configuration/administration read: '+path)
assert not call('/api/v1/auth/session')['authenticated']
assert not call('/api/session/api/v1/auth/session')['authenticated']
checks.append('administrator, user and API cookies isolated')
user = call('/api/v1/auth/session')
call('/api/v1/auth/otp/send',{'phone':'19900000001'},user['csrfToken'])
call('/api/v1/auth/otp/login',{'phone':'19900000001','code':'123456'},user['csrfToken'])
user = call('/api/v1/auth/session')
assert user['authenticated'] and 'user' in {r.lower() for r in user['roles']}
profile = call('/api/v1/users/me')
if not call('/api/v1/access/keys'):
    call('/admin/api/v1/admin/payments/accounts/'+profile['id'],
         {'requestId':'local-runtime-personal-'+profile['id'],'accountType':'PERSONAL','createKey':True},
         session['csrfToken'],method='PATCH')
options = call('/api/v1/media/generation-options')
assert options['models'], 'Production generation catalog must offer models to the local PERSONAL test user'
call('/api/v1/canvases')
assert call('/admin/api/v1/auth/session')['authenticated']
checks.append('local OTP user login, profile, generation options and canvas reads')
assert all(not cookie.secure for cookie in cookies)
checks.append('HTTP cookies usable on loopback')
call('/admin/api/v1/auth/logout',{},session['csrfToken'])
call('/api/v1/auth/logout',{},user['csrfToken'])
(STATE/'http-verification.json').write_text(json.dumps({'checks':checks,'paidRequestsSubmitted':False,
    'localTestPhone':'19900000001'},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
for check in checks:
    print('PASS',check,flush=True)
