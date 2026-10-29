"""Check the isolated neibu stack through Docker health and actual login APIs."""
from datetime import datetime, timezone
import http.cookiejar
import json
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent
STATE = ROOT / 'target'
BASE = 'http://127.0.0.1:18390'
COMPOSE = ['docker', 'compose', '--project-directory', str(ROOT), '--env-file',
           str(STATE / '.env.compose'), '-f', str(STATE / 'compose.json')]


def require(condition, description):
    if not condition:
        raise RuntimeError(description)


def main():
    private = json.loads((STATE / '.env.credentials.json').read_text(encoding='utf-8'))
    process = subprocess.run([*COMPOSE, 'ps', '--format', 'json'], capture_output=True,
                             text=True, encoding='utf-8', check=True)
    rows = [json.loads(line) for line in process.stdout.splitlines() if line.strip()]
    if len(rows) == 1 and isinstance(rows[0], list):
        rows = rows[0]
    require(len(rows) == 15, 'Expected 15 containers belonging to neibu.')
    require(all(row['State'] == 'running' and row['Health'] == 'healthy' for row in rows),
            'Every neibu container must be running and healthy.')
    require(any(row['Name'] == 'neibu' for row in rows), 'The entry container must be named neibu.')
    checks = ['All 15 containers running with successful health probes']
    query = "SELECT TABLE_SCHEMA FROM information_schema.TABLES WHERE TABLE_NAME='flyway_schema_history' AND TABLE_SCHEMA LIKE 'neibu_%';"
    result = subprocess.run([*COMPOSE, 'exec', '-T', 'mysql', 'sh', '-c',
                             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -Nse "$1"', 'sh', query],
                            capture_output=True, text=True, encoding='utf-8', check=True)
    migrated = result.stdout.splitlines()
    require(len(migrated) == 11, 'Expected 11 databases initialized by Flyway.')
    checks.append('All 11 service databases migrated by Flyway')
    cookies = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                         urllib.request.HTTPCookieProcessor(cookies))

    def call(path, data=None, csrf=None, method=None, base=BASE):
        headers = {'Content-Type': 'application/json'}
        if csrf:
            headers['X-CSRF-TOKEN'] = csrf
        request = urllib.request.Request(base + path,
            json.dumps(data).encode() if data is not None else None, headers=headers, method=method)
        try:
            with client.open(request, timeout=30) as response:
                payload = response.read()
                if 'application/json' in response.headers.get('Content-Type', ''):
                    return json.loads(payload) if payload else None
                return payload.decode('utf-8', errors='replace')
        except urllib.error.HTTPError as error:
            raise RuntimeError(f'Local API returned {error.code}: {path}') from None

    for path in ('/', '/commerce/detail-page', '/admin/login', '/api/login', '/promoter/overview'):
        html = call(path)
        require('<html' in html.lower(), 'Page did not render: ' + path)
        for asset in re.findall(r'(?:src|href)="(/assets/[^\"]+)"', html):
            response = client.open(BASE + asset, timeout=30)
            with response:
                require('text/html' not in response.headers.get('Content-Type', ''),
                        'Asset unexpectedly returned HTML: ' + asset)
                response.read()
        checks.append('Page and assets: ' + path)
    require('<html' in call('/', base='http://127.0.0.1:18392').lower(), 'Partner frontend unavailable.')
    checks.append('Partner frontend')
    for path in ('/homepage-v2/background.mp4', '/new-frontend/commerce/models/phone.glb'):
        request = urllib.request.Request(BASE + path, headers={'Range': 'bytes=0-1023'})
        with client.open(request, timeout=30) as response:
            require(response.status == 206 and len(response.read()) == 1024,
                    'LFS asset unavailable or range requests failed: ' + path)
    checks.append('Background video and 3D model served with HTTP range support')
    admin = call('/admin/api/v1/auth/session')
    call('/admin/api/v1/auth/admin/login', {
        'username': private['IAM_BOOTSTRAP_ADMIN_USERNAME'],
        'password': private['IAM_BOOTSTRAP_ADMIN_PASSWORD']}, admin['csrfToken'])
    admin = call('/admin/api/v1/auth/session')
    require(admin['authenticated'], 'Administrator login failed.')
    checks.append('Administrator password login')
    for path in ('users', 'admins', 'media/provider-status', 'billing/media-prices', 'operations/users'):
        call('/admin/api/v1/admin/' + path)
        checks.append('Administration read: ' + path)
    user = call('/api/v1/auth/session')
    require(not user['authenticated'], 'Administrator session leaked into user portal.')
    call('/api/v1/auth/otp/send', {'phone': '19900000001'}, user['csrfToken'])
    call('/api/v1/auth/otp/login', {'phone': '19900000001', 'code': '123456'}, user['csrfToken'])
    user = call('/api/v1/auth/session')
    require(user['authenticated'], 'Local fixed-code user login failed.')
    profile = call('/api/v1/users/me')
    require(bool(profile.get('id')), 'User profile unavailable.')
    checks.append('Fixed-code user login, profile and cookie isolation')
    call('/api/v1/canvases')
    checks.append('Canvas service through the authenticated gateway')
    report = {'checkedAtUTC': datetime.now(timezone.utc).isoformat(), 'checks': checks,
              'containers': len(rows), 'migratedDatabases': migrated,
              'testPhone': '19900000001', 'paidGenerationSubmitted': False,
              'externalProviderCredentialsConfigured': False}
    (STATE / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for check in checks:
        print('PASS', check)


if __name__ == '__main__':
    main()
