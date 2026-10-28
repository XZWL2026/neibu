"""Local runtime for this checkout only. Secrets and state stay in ignored target/."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent
STATE = ROOT / 'target'
PORTS = {'iam':8210,'user':8211,'access':8212,'promotion':8213,'billing':8214,
         'payment':8215,'routing':8216,'media':8217,'canvas':8218,'storage':8219,
         'operations':8220,'gateway':8200}
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0

def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def run(args, *, data=None, private=False):
    p = subprocess.run([str(a) for a in args], cwd=REPO, input=data,
                       capture_output=True, creationflags=FLAGS)
    if p.returncode:
        STATE.mkdir(exist_ok=True)
        (STATE / '.env.last-command-error').write_bytes(p.stdout + p.stderr)
        raise RuntimeError('Command failed: ' + str(args[0]) + '; details in deployment/local/target/.env.last-command-error')
    if not private and p.stdout:
        print(p.stdout.decode('utf-8', errors='replace').strip(), flush=True)
    return p.stdout

def compose(*args, private=False, data=None):
    return run(['docker','compose','--env-file',STATE/'.env.compose','-f',ROOT/'compose.yml',*args],
               private=private, data=data)

def request(path, fields=None, method=None):
    url = path if path.startswith('http') else 'http://127.0.0.1:28848/nacos' + path
    data = urllib.parse.urlencode(fields).encode() if fields is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    with HTTP.open(req, timeout=10) as response:
        value = response.read().decode()
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return value

def export():
    STATE.mkdir(exist_ok=True)
    script = (ROOT/'export-production.py').read_bytes()
    nodes = [('146',['-i',Path.home()/'.ssh/XZWL_47_97_85_146.pem','root@47.97.85.146'],'environment'),
             ('62',['47.99.240.62'],'environment'),('124',['115.29.222.124'],'database')]
    for label,args,mode in nodes:
        obj = json.loads(run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10',*args,
                              'python3 - ' + mode], data=script, private=True))
        write_json(STATE/('.env.production-'+label+'.json'),obj)
        print('Read-only production export:',label,flush=True)

def prepare():
    STATE.mkdir(exist_ok=True)
    env_path = STATE/'.env.runtime.json'
    if env_path.exists():
        settings = read_json(env_path)
    else:
        settings = {}
        legacy = REPO/'.scratch/local-runtime/.env.local'
        if legacy.exists():
            settings = read_json(legacy)
        for key in ('LOCAL_MYSQL_ROOT_PASSWORD','LOCAL_DB_PASSWORD','ABP_INTERNAL_TOKEN',
                    'IAM_USER_INTERNAL_TOKEN','IAM_OTP_HMAC_KEY','MEDIA_STORAGE_TOKEN',
                    'MEDIA_ROUTING_TOKEN','LOCAL_REDIS_PASSWORD','LOCAL_NACOS_PASSWORD',
                    'LOCAL_NACOS_IDENTITY'):
            settings.setdefault(key,secrets.token_hex(32))
        for key in ('ACCESS_KEY_ENCRYPTION_KEY','MEDIA_CONFIG_ENCRYPTION_KEY','LOCAL_NACOS_AUTH_TOKEN'):
            settings.setdefault(key,base64.b64encode(secrets.token_bytes(32)).decode())
        settings.setdefault('IAM_BOOTSTRAP_ADMIN_USERNAME','localadmin')
        settings.setdefault('IAM_BOOTSTRAP_ADMIN_PASSWORD','Local-'+secrets.token_hex(10)+'1!')
    production = {}
    for label in ('146','62'):
        production.update(read_json(STATE/('.env.production-'+label+'.json'))['services'])
    # Copy actual service configuration, then overwrite every infrastructure address.
    envs = {}
    for service in PORTS:
        env = dict(production[service])
        local_keys = {'ABP_INTERNAL_TOKEN','IAM_USER_INTERNAL_TOKEN','IAM_OTP_HMAC_KEY',
                      'ACCESS_KEY_ENCRYPTION_KEY','MEDIA_STORAGE_TOKEN','MEDIA_ROUTING_TOKEN',
                      'IAM_BOOTSTRAP_ADMIN_USERNAME','IAM_BOOTSTRAP_ADMIN_PASSWORD'}
        for key,value in settings.items():
            if key in local_keys:
                env[key] = str(value)
        env.update(SPRING_PROFILES_ACTIVE='prod' if service not in ('iam','canvas','operations') else 'local',
                   SERVER_ADDRESS='127.0.0.1',SERVICE_BIND_ADDRESS='127.0.0.1',
                   NACOS_SERVER_ADDR='127.0.0.1:28848',NACOS_NAMESPACE='xzwl-local',
                   NACOS_USERNAME='nacos',NACOS_PASSWORD=settings['LOCAL_NACOS_PASSWORD'],
                   SPRING_CLOUD_NACOS_CONFIG_ENABLED='true',SPRING_CLOUD_NACOS_DISCOVERY_ENABLED='true',
                   IAM_LOCAL_OTP_ENABLED='true',IAM_LOCAL_OTP_CODE='123456',
                   IAM_SMS_PROVIDER='disabled',IAM_SMS_ENABLED='false',
                   MEDIA_BILLING_TOKEN=settings['ABP_INTERNAL_TOKEN'],
                   STORAGE_MEDIA_TOKEN=settings['MEDIA_STORAGE_TOKEN'],ROUTING_MEDIA_TOKEN=settings['MEDIA_ROUTING_TOKEN'],
                   PAYMENT_PROVIDER='disabled',PAYMENT_WORKER_ENABLED='true')
        if service == 'iam':
            for kind in ('CONFIG','DISCOVERY'):
                prefix = 'SPRING_CLOUD_NACOS_'+kind+'_'
                env.update({prefix+'SERVER_ADDR':'127.0.0.1:28848',prefix+'NAMESPACE':'xzwl-local',
                            prefix+'GROUP':'XZWL',prefix+'USERNAME':'nacos',
                            prefix+'PASSWORD':settings['LOCAL_NACOS_PASSWORD']})
            env['SPRING_CLOUD_NACOS_DISCOVERY_IP'] = '127.0.0.1'
            env['XZWL_DISCOVERY_ENABLED'] = 'true'
            env['SPRING_CONFIG_IMPORT'] = 'nacos:xzwl-common.yaml?group=XZWL&refreshEnabled=false,nacos:iam-service.yaml?group=XZWL&refreshEnabled=false'
        # Encrypted provider connections must keep the production encryption key.
        for key in ('MEDIA_CONFIG_ENCRYPTION_KEY','VOLC_ARK_API_KEY'):
            if key in production.get('media',{}):
                env[key] = production['media'][key]
        for target,port in PORTS.items():
            upper = target.upper()
            env[upper+'_SERVICE_URL'] = ('http://127.0.0.1:'+str(port) if service in ('canvas','operations')
                                          else 'http://'+target+'-service')
            env['GATEWAY_'+upper+'_URI'] = 'lb://'+target+'-service'
        if service != 'gateway':
            env[service.upper()+'_DB_URL'] = 'jdbc:mysql://127.0.0.1:23306/xzwl_local_'+service+'?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=utf8'
            env[service.upper()+'_DB_USERNAME'] = 'xzwl_local'
            env[service.upper()+'_DB_PASSWORD'] = settings['LOCAL_DB_PASSWORD']
        env['USER_STORAGE_URL'] = 'http://127.0.0.1:8219'
        if service == 'billing':
            # Empty Feign URL activates service discovery; a service-name URL would use DNS directly.
            env['IAM_SERVICE_URL'] = ''
        env['STORAGE_FFPROBE_PATH'] = str(REPO/'.scratch/ivne-tools/ffmpeg-9.0.2-essentials_build/bin/ffprobe.exe')
        # These two modules do not ship Nacos starters. Register them explicitly.
        if service in ('canvas','operations'):
            env['SPRING_CLOUD_NACOS_CONFIG_ENABLED'] = 'false'
            env['SPRING_CLOUD_NACOS_DISCOVERY_ENABLED'] = 'false'
        envs[service] = env
    write_json(env_path,settings)
    write_json(STATE/'.env.services.json',envs)
    (STATE/'.env.compose').write_text('\n'.join(k+'='+str(v) for k,v in settings.items() if k.startswith('LOCAL_'))+'\n',encoding='utf-8')
    (STATE/'.env.redis-password').write_text(settings['LOCAL_REDIS_PASSWORD'],encoding='utf-8')
    (STATE/'redis.conf').write_text('bind 0.0.0.0\nprotected-mode yes\nappendonly yes\nrequirepass '+settings['LOCAL_REDIS_PASSWORD']+'\n',encoding='utf-8')
    init = STATE/'mysql-init'
    init.mkdir(exist_ok=True)
    sql = ["CREATE USER IF NOT EXISTS 'xzwl_local'@'%' IDENTIFIED BY '"+settings['LOCAL_DB_PASSWORD']+"';"]
    for name in [*('xzwl_local_'+s for s in PORTS if s!='gateway'),'nacos_local']:
        sql += ['CREATE DATABASE IF NOT EXISTS `'+name+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;',
                'GRANT ALL ON `'+name+"`.* TO 'xzwl_local'@'%';"]
    (init/'01-schemas.sql').write_text('\n'.join(sql),encoding='utf-8')
    schema = read_json(STATE/'.env.production-124.json')['nacosSchema']
    (init/'02-nacos.sql').write_text('USE nacos_local;\n'+schema,encoding='utf-8')
    run(['node',ROOT/'localize-config.cjs'])
    credentials = 'Local administrator: '+settings['IAM_BOOTSTRAP_ADMIN_USERNAME']+'\nPassword: '+settings['IAM_BOOTSTRAP_ADMIN_PASSWORD']+'\nLocal OTP: 123456\nNacos user: nacos\nNacos password: '+settings['LOCAL_NACOS_PASSWORD']+'\nMySQL: 127.0.0.1:23306 / xzwl_local\nMySQL password: '+settings['LOCAL_DB_PASSWORD']+'\nRedis: 127.0.0.1:26379\nRedis password: '+settings['LOCAL_REDIS_PASSWORD']+'\n'
    (STATE/'.env.credentials.txt').write_text(credentials,encoding='utf-8')
    print('Private local configuration prepared; 11 isolated schemas + nacos_local',flush=True)

def nacos_token():
    env = read_json(STATE/'.env.runtime.json')
    return request('/v1/auth/login',{'username':'nacos','password':env['LOCAL_NACOS_PASSWORD']})['accessToken']

def nacos_init():
    settings = read_json(STATE/'.env.runtime.json')
    try:
        token = nacos_token()
    except (urllib.error.HTTPError, KeyError):
        request('/v1/auth/users/admin',{'password':settings['LOCAL_NACOS_PASSWORD']})
        token = nacos_token()
    tenants = request('/v1/console/namespaces?'+urllib.parse.urlencode({'accessToken':token}))
    if not any(t['namespace']=='xzwl-local' for t in tenants['data']):
        request('/v1/console/namespaces',{'accessToken':token,'customNamespaceId':'xzwl-local',
                                        'namespaceName':'xzwl-local','namespaceDesc':'Local current checkout'})
    for c in read_json(STATE/'.env.nacos-local.json'):
        fields = {'accessToken':token,'tenant':'xzwl-local','group':c['group'],'dataId':c['dataId'],
                  'content':c['content'],'type':'yaml'}
        if request('/v1/cs/configs',fields) is not True:
            raise RuntimeError('Nacos configuration publication failed: '+c['dataId'])
        actual = None
        for attempt in range(20):
            try:
                actual = request('/v1/cs/configs?'+urllib.parse.urlencode({k:v for k,v in fields.items() if k not in ('content','type')}))
                if actual == c['content']:
                    break
            except urllib.error.HTTPError as exc:
                if exc.code != 404:
                    raise
            time.sleep(.5)
        if actual != c['content']:
            raise RuntimeError('Nacos readback differs: '+c['dataId'])
    print('Nacos local namespace: 11 configurations published and read back',flush=True)

def infra():
    compose('up','-d','--wait','--wait-timeout','240','mysql','redis','nacos')
    nacos_init()

def processes():
    path = STATE/'processes.json'
    return read_json(path) if path.exists() else {}

def process_command(pid):
    script = "(Get-CimInstance Win32_Process -Filter 'ProcessId="+str(int(pid))+"').CommandLine"
    return run(['powershell','-NoProfile','-Command',script],private=True).decode(errors='replace')

def stop(adopt=False, only=None):
    records = processes()
    if adopt:
        old = REPO/'.scratch/local-runtime/processes.json'
        if old.exists():
            records = {**{k:{**v,'adopted':True} for k,v in read_json(old).items() if k in PORTS},**records}
    for service,record in list(records.items()):
        if only and service != only:
            continue
        pid = int(record['pid'])
        command = process_command(pid).replace('\\','/').lower()
        expected_module = '/'+service+'-service/'
        if command and 'java' in command and str(REPO).replace('\\','/').lower() in command and '-jar' in command and expected_module in command:
            run(['taskkill','/PID',str(pid),'/F'],private=True)
            print('Stopped local',service,flush=True)
        elif command and not record.get('adopted'):
            raise RuntimeError('PID ownership changed; refusing to stop '+service)
        if service in ('canvas','operations'):
            try:
                request('/v1/ns/instance',{'accessToken':nacos_token(),'namespaceId':'xzwl-local',
                    'groupName':'XZWL','serviceName':service+'-service','clusterName':'DEFAULT',
                    'ip':'127.0.0.1','port':PORTS[service],'ephemeral':'false'},method='DELETE')
            except (urllib.error.URLError,KeyError):
                pass
        records.pop(service,None)
    write_json(STATE/'processes.json',records)

def start(adopt=False):
    if adopt:
        stop(adopt=True)
    envs = read_json(STATE/'.env.services.json')
    records = processes()
    logs = STATE/'logs'
    logs.mkdir(exist_ok=True)
    java = shutil.which('java')
    if not java:
        raise RuntimeError('Java 21 is required')
    for service,port in PORTS.items():
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1',port))==0:
                record = records.get(service)
                if record and str(REPO).replace('\\','/').lower() in process_command(record['pid']).replace('\\','/').lower():
                    print('Already running',service,flush=True)
                    continue
                raise RuntimeError('Port already in use: '+str(port))
        module = REPO/('gateways/gateway-service' if service=='gateway' else 'services/'+service+'-service/'+service+'-bootstrap')
        jars = sorted((module/'target').glob('*-exec.jar')) or sorted((module/'target').glob('*.jar'))
        jars = [p for p in jars if not p.name.endswith(('-sources.jar','-tests.jar'))]
        if not jars:
            raise RuntimeError('Build artifact missing for '+service)
        jar = jars[0]
        args = [java,'-Xms64m','-Xmx384m','-XX:MaxMetaspaceSize=192m','-Dfile.encoding=UTF-8','-jar',str(jar)]
        if service == 'gateway':
            args += ['--server.address=0.0.0.0']
        if service == 'iam':
            args += ['--server.servlet.session.cookie.secure=false','--iam.local-otp.enabled=true','--iam.sms.provider=disabled']
        if service == 'payment':
            # The disabled provider blocks online payments; the worker fulfills local manual recharges.
            args += ['--payment.provider=disabled','--payment.worker-enabled=true']
        env = {**os.environ,**{k:str(v) for k,v in envs[service].items()}}
        with (logs/(service+'.log')).open('wb') as out, (logs/(service+'.stderr.log')).open('wb') as err:
            p = subprocess.Popen(args,cwd=REPO,env=env,stdout=out,stderr=err,creationflags=FLAGS)
        records[service] = {'pid':p.pid,'port':port,'jar':str(jar)}
        write_json(STATE/'processes.json',records)
        print('Started',service,port,flush=True)
        time.sleep(.5)

def mysql(sql):
    return compose('exec','-T','mysql','sh','-c',
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names --default-character-set=utf8mb4',
                   data=sql.encode(),private=True).decode('utf-8').splitlines()

def literal(value):
    return 'NULL' if value is None else "CONVERT(X'"+str(value).encode().hex()+"' USING utf8mb4)"

def import_config():
    source = read_json(STATE/'.env.production-124.json')
    active = mysql("SELECT COUNT(*) FROM xzwl_local_media.media_task WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED');")
    if active != ['0']:
        raise RuntimeError('Local media has active work; configuration import refused')
    queries = ['START TRANSACTION;']
    backups = {}
    for name,table in source['tables'].items():
        service,table_name = name.split('.')
        schema = 'xzwl_local_'+service
        cols = table['columns']
        actual_cols = mysql("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='"+schema+"' AND TABLE_NAME='"+table_name+"' ORDER BY ORDINAL_POSITION;")
        if set(cols)-set(actual_cols):
            raise RuntimeError('Migration required before configuration import: '+name)
        if name=='payment.payment_provider_state' and any(not r['id'].startswith('terminal:') for r in table['rows']):
            raise RuntimeError('Payment export contains business order state')
        pairs = ','.join("'"+c+"',CAST(`"+c+"` AS CHAR CHARACTER SET utf8mb4)" for c in cols)
        select = 'SELECT JSON_OBJECT('+pairs+') FROM `'+schema+'`.`'+table_name+'`;'
        backups[name] = [json.loads(r) for r in mysql(select)]
        queries += ['DELETE FROM `'+schema+'`.`'+table_name+'`;']
        for row in table['rows']:
            queries += ['INSERT INTO `'+schema+'`.`'+table_name+'` ('+','.join('`'+c+'`' for c in cols)+') VALUES ('+','.join(literal(row[c]) for c in cols)+');']
    write_json(STATE/('.env.config-backup-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'.json'),backups)
    queries += ['COMMIT;']
    mysql('\n'.join(queries))
    counts = {}
    for name,table in source['tables'].items():
        service,table_name = name.split('.')
        pairs = ','.join("'"+c+"',CAST(`"+c+"` AS CHAR CHARACTER SET utf8mb4)" for c in table['columns'])
        rows = [json.loads(r) for r in mysql('SELECT JSON_OBJECT('+pairs+') FROM `xzwl_local_'+service+'`.`'+table_name+'`;')]
        canon = lambda r: json.dumps(r,sort_keys=True)
        if sorted(map(canon,rows)) != sorted(map(canon,table['rows'])):
            raise RuntimeError('Production configuration readback differs: '+name)
        counts[name] = len(rows)
    write_json(STATE/'import-report.json',{'source':source['source'],'tables':counts,
                                         'remoteModified':False,'productionBusinessDataCopied':False})
    print('Production configuration imported and compared:',json.dumps(counts),flush=True)

def register_extra():
    token = nacos_token()
    for service in ('canvas','operations'):
        fields = {'accessToken':token,'namespaceId':'xzwl-local','groupName':'XZWL',
                  'serviceName':service+'-service','ip':'127.0.0.1','port':PORTS[service],
                  'ephemeral':'false','healthy':'true','enabled':'true',
                  'metadata':json.dumps({'local-checkout':str(REPO)})}
        request('/v1/ns/instance',fields)
        # Nacos runs in Docker: server-side TCP checks against 127.0.0.1 cannot see Windows.
        # These modules have no Nacos client; the runtime verifier owns their local health.
        request('/v1/ns/cluster',{'accessToken':token,'namespaceId':'xzwl-local','groupName':'XZWL',
            'serviceName':service+'-service','clusterName':'DEFAULT',
            'healthChecker':json.dumps({'type':'NONE'}),'metadata':'{}',
            'checkPort':PORTS[service],'useInstancePort4Check':'true'},method='PUT')
        request('/v1/ns/instance',fields,method='PUT')
    print('Canvas and operations registered in local Nacos',flush=True)

def verify():
    failures = []
    for service,port in PORTS.items():
        try:
            path = '/api/v1/auth/session' if service=='iam' else '/actuator/health'
            status = request('http://127.0.0.1:'+str(port)+path)
            ready = 'csrfToken' in status if service=='iam' else status.get('status')=='UP'
            if not ready:
                failures.append(service)
            print(service,'READY' if ready else 'NOT READY',flush=True)
        except urllib.error.HTTPError as exc:
            if service in ('canvas','operations') and exc.code==404:
                protected = '/api/v1/canvases' if service=='canvas' else '/api/v1/admin/operations/users'
                try:
                    request('http://127.0.0.1:'+str(port)+protected)
                    failures.append(service)
                except urllib.error.HTTPError as auth:
                    if auth.code!=401:
                        failures.append(service)
                    else:
                        print(service,'READY (authenticated API)',flush=True)
            else:
                failures.append(service)
                print(service,'UNAVAILABLE',flush=True)
        except Exception:
            failures.append(service)
            print(service,'UNAVAILABLE',flush=True)
    if failures:
        raise RuntimeError('Services not ready: '+', '.join(failures)+'; check target/logs')
    register_extra()
    token = nacos_token()
    discovered = {}
    for service in PORTS:
        value = request('/v1/ns/instance/list?'+urllib.parse.urlencode({'accessToken':token,
                         'namespaceId':'xzwl-local','groupName':'XZWL','serviceName':service+'-service'}))
        hosts = value.get('hosts',[])
        if not hosts or any(h['ip']!='127.0.0.1' or not h['healthy'] for h in hosts):
            raise RuntimeError('Unexpected local Nacos registration: '+service)
        discovered[service] = [h['port'] for h in hosts]
    migrations = mysql("SELECT TABLE_SCHEMA FROM information_schema.TABLES WHERE TABLE_NAME='flyway_schema_history' AND TABLE_SCHEMA LIKE 'xzwl_local_%';")
    if len(migrations)!=11:
        raise RuntimeError('Expected eleven migrated local databases')
    write_json(STATE/'verification.json',{'services':'READY','discovery':discovered,
                                        'migratedDatabases':migrations,'checkedAtUTC':datetime.now(timezone.utc).isoformat()})
    print('All 12 services ready; Nacos registrations local; 11 migrated databases',flush=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action',choices=['export','prepare','infra','nacos-init','start','stop','import','verify','status','web'])
    parser.add_argument('--adopt-existing',action='store_true')
    parser.add_argument('--service',choices=list(PORTS))
    args = parser.parse_args()
    if args.action=='export': export()
    elif args.action=='prepare': prepare()
    elif args.action=='infra': infra()
    elif args.action=='nacos-init': nacos_init()
    elif args.action=='start': start(args.adopt_existing)
    elif args.action=='stop': stop(args.adopt_existing,args.service)
    elif args.action=='import': import_config()
    elif args.action=='verify': verify()
    elif args.action=='status': compose('--profile','web','ps')
    elif args.action=='web': compose('--profile','web','up','-d','--wait','nginx')

if __name__=='__main__':
    try:
        main()
    except Exception as exc:
        print(str(exc),file=sys.stderr)
        sys.exit(1)
