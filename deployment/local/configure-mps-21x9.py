import argparse
import http.cookiejar
import json
import pathlib
import urllib.error
import urllib.request

PROJECT = pathlib.Path(__file__).resolve().parents[2]
STATE = PROJECT / 'deployment/local/target'
BASE = 'http://127.0.0.1:18090'
OUT = STATE / 'mps-21x9-verification.json'
PATH = '/admin/api/v1/admin/media/mps-configuration'
jar = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar))

def call(path, data=None, csrf=None, method=None, timeout=45, extra_headers=None):
    assert path.startswith('/') and not path.startswith('//')
    headers = {'Content-Type': 'application/json'}
    headers.update(extra_headers or {})
    if csrf:
        headers['X-CSRF-TOKEN'] = csrf
    req = urllib.request.Request(BASE + path, data=json.dumps(data).encode() if data is not None else None, headers=headers, method=method)
    try:
        with client.open(req, timeout=timeout) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        try:
            body = json.load(error)
            detail = {key: body.get(key) for key in ['code', 'message', 'detail', 'title', 'error'] if key in body}
        except (ValueError, TypeError):
            detail = {}
        raise RuntimeError(f'Local HTTP {error.code}: {path} {json.dumps(detail, ensure_ascii=False)}') from None

def login():
    environments = json.loads((STATE / '.env.services.json').read_text(encoding='utf-8'))
    assert environments['media']['MEDIA_DB_URL'].startswith('jdbc:mysql://127.0.0.1:23306/xzwl_local_media?'), 'Expected isolated local media database'
    credentials = json.loads((STATE / '.env.runtime.json').read_text(encoding='utf-8'))
    session = call('/admin/api/v1/auth/session')
    call('/admin/api/v1/auth/admin/login', {'username': credentials['IAM_BOOTSTRAP_ADMIN_USERNAME'], 'password': credentials['IAM_BOOTSTRAP_ADMIN_PASSWORD']}, session['csrfToken'])
    session = call('/admin/api/v1/auth/session')
    assert session['authenticated'] and 'admin' in {r.lower() for r in session['roles']}
    return session['csrfToken']

def safe_config(config):
    return json.loads(json.dumps({key: config.get(key) for key in ['version', 'source', 'region', 'bucket', 'bucketRegion', 'outputDir', 'secretKeyConfigured', 'policyConfigured', 'enhancementTemplates', 'bitrateProfiles']}))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['inspect', 'apply', 'probe'])
    args = parser.parse_args()
    csrf = login()
    config = call(PATH)
    result = {'project': str(PROJECT), 'base': BASE, 'before': safe_config(config)}
    if args.action == 'apply':
        OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        assert config['policyConfigured'], 'Expected existing policy'
        templates = config['enhancementTemplates']
        selected = [t for t in templates if t['outputResolution'] == '720p' and t['enabled']]
        assert len(selected) == 1, 'Expected one enabled 720p template'
        row = selected[0]
        assert '480p' in row['acceptedInputResolutions']
        desired_schedules = {'WIDE': 108412, 'SQUARE': 108413, 'STANDARD': 108414, 'ULTRAWIDE': 108415}
        unchanged = row['definitionId'] is None and row['scheduleIds'] == desired_schedules
        row['definitionId'] = None
        row['scheduleIds'] = desired_schedules
        payload = {key: config[key] for key in ['secretId', 'region', 'bucket', 'bucketRegion', 'outputDir', 'template720p', 'template1080p', 'template4k', 'bitrateProfiles']}
        payload.update(expectedVersion=config['version'], secretKey='', enhancementTemplates=templates)
        if not unchanged:
            config = call(PATH, payload, csrf, 'PUT')
        result['after'] = safe_config(config)
        OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    if args.action in ['apply', 'probe']:
        tests = []
        for template in config['enhancementTemplates']:
            if not template['enabled']:
                continue
            ratios = ['16:9', '9:16', '1:1', '4:3', '3:4', '21:9'] if template['outputResolution'] == '720p' else ['21:9']
            for ratio in ratios:
                evidence = call(PATH + '/test', {'expectedVersion': config['version'], 'templateId': template['id'], 'aspectRatio': ratio}, csrf)
                if template['outputResolution'] == '720p':
                    expected = {'16:9': (1280, 720), '9:16': (720, 1280), '1:1': (960, 960), '4:3': (1112, 834), '3:4': (834, 1112), '21:9': (1470, 630)}[ratio]
                else:
                    expected = {'1080p': (2206, 946), '4K': (4398, 1886)}[template['outputResolution']]
                evidence['expectedDimensions'] = list(expected)
                evidence['success'] = evidence['success'] and (evidence['width'], evidence['height']) == expected
                tests.append({'templateId': template['id'], 'resolution': template['outputResolution'], 'aspectRatio': ratio, **evidence})
                print(json.dumps(tests[-1], ensure_ascii=False), flush=True)
        result['tests'] = tests
        result['passed'] = all(t['success'] for t in tests)
        if args.action == 'probe' and OUT.exists():
            previous = json.loads(OUT.read_text(encoding='utf-8'))
            result = {**previous, 'current': safe_config(config), 'tests': tests, 'passed': result['passed']}
        OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        assert result['passed'], 'Local MPS configuration checks failed'
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    call('/admin/api/v1/auth/logout', {}, csrf)

if __name__ == '__main__':
    main()
