"""Prepare an independent Docker development stack; never reads production state."""
import base64
import json
from pathlib import Path
import secrets
import shutil

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent
STATE = ROOT / 'target'
PORTS = {'iam': 8210, 'user': 8211, 'access': 8212, 'promotion': 8213,
         'billing': 8214, 'payment': 8215, 'routing': 8216, 'media': 8217,
         'canvas': 8218, 'storage': 8219, 'operations': 8220, 'gateway': 8200}


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def prepare():
    STATE.mkdir(exist_ok=True)
    for name in ('env', 'mysql-init', 'jars'):
        (STATE / name).mkdir(exist_ok=True)
    credentials = STATE / '.env.credentials.json'
    if credentials.exists():
        private = json.loads(credentials.read_text(encoding='utf-8'))
    else:
        private = {key: secrets.token_hex(32) for key in (
            'MYSQL_ROOT_PASSWORD', 'DB_PASSWORD', 'REDIS_PASSWORD', 'ABP_INTERNAL_TOKEN',
            'IAM_USER_INTERNAL_TOKEN', 'IAM_OTP_HMAC_KEY', 'MEDIA_STORAGE_TOKEN', 'MEDIA_ROUTING_TOKEN')}
        for key in ('ACCESS_KEY_ENCRYPTION_KEY', 'MEDIA_CONFIG_ENCRYPTION_KEY'):
            private[key] = base64.b64encode(secrets.token_bytes(32)).decode()
        private.update(IAM_BOOTSTRAP_ADMIN_USERNAME='localadmin',
                       IAM_BOOTSTRAP_ADMIN_PASSWORD='Dev-' + secrets.token_hex(10) + '1!')
        write_json(credentials, private)
    (STATE / '.env.compose').write_text('\n'.join(key + '=' + private[key] for key in (
        'MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD')) + '\n', encoding='utf-8')
    sql = ["CREATE USER IF NOT EXISTS 'neibu'@'%' IDENTIFIED BY '" + private['DB_PASSWORD'] + "';"]
    for service in PORTS:
        if service == 'gateway':
            continue
        database = 'neibu_' + service
        sql.extend(['CREATE DATABASE IF NOT EXISTS `' + database + '` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;',
                    'GRANT ALL ON `' + database + "`.* TO 'neibu'@'%';"])
    (STATE / 'mysql-init/01-schemas.sql').write_text('\n'.join(sql) + '\n', encoding='utf-8')
    common = {key: value for key, value in private.items() if key not in ('MYSQL_ROOT_PASSWORD', 'DB_PASSWORD')}
    common.update({
        'TZ': 'Asia/Shanghai', 'SERVER_ADDRESS': '0.0.0.0', 'SERVICE_BIND_ADDRESS': '0.0.0.0',
        'SPRING_CLOUD_NACOS_CONFIG_ENABLED': 'false', 'SPRING_CLOUD_NACOS_DISCOVERY_ENABLED': 'false',
        'SPRING_CLOUD_NACOS_CONFIG_IMPORT_CHECK_ENABLED': 'false', 'XZWL_DISCOVERY_ENABLED': 'false',
        'SPRING_CLOUD_DISCOVERY_ENABLED': 'false', 'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': '5',
        'SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE': '20MB', 'SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE': '21MB',
        'IAM_LOCAL_OTP_ENABLED': 'true', 'IAM_LOCAL_OTP_CODE': '123456', 'IAM_SMS_PROVIDER': 'disabled',
        'SPRING_PROFILES_ACTIVE': 'local', 'SERVER_SERVLET_SESSION_COOKIE_SECURE': 'false',
        'PAYMENT_PROVIDER': 'disabled', 'PAYMENT_WORKER_ENABLED': 'true',
        'MEDIA_WORKER_ENABLED': 'true', 'MEDIA_WORKER_CONCURRENCY': '2',
        'MEDIA_BILLING_TOKEN': private['ABP_INTERNAL_TOKEN'], 'STORAGE_MEDIA_TOKEN': private['MEDIA_STORAGE_TOKEN'],
        'ROUTING_MEDIA_TOKEN': private['MEDIA_ROUTING_TOKEN'], 'STORAGE_RESULT_MAX_BYTES': '104857600', 'STORAGE_FFPROBE_PATH': '/usr/bin/ffprobe',
        'REDIS_HOST': 'redis', 'REDIS_PORT': '6379', 'XZWL_CACHE_ENABLED': 'false', 'CACHE_ENVIRONMENT': 'neibu',
        'JAVA_TOOL_OPTIONS': '-Xms64m -Xmx320m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError',
        'BILLING_HTTP_DISCOVERY_ENABLED': 'false',
    })
    for service, port in PORTS.items():
        common[service.upper() + '_SERVICE_URL'] = f'http://{service}:{port}'
        common['GATEWAY_' + service.upper() + '_URI'] = f'http://{service}:{port}'
        for caller in PORTS:
            common[caller.upper() + '_' + service.upper() + '_SERVICE_URL'] = f'http://{service}:{port}'
    for key, service in {
        'MEDIA_USER_URL': 'user', 'MEDIA_IAM_URL': 'iam', 'MEDIA_BILLING_URL': 'billing',
        'MEDIA_ROUTING_URL': 'routing', 'MEDIA_STORAGE_URL': 'storage', 'USER_STORAGE_URL': 'storage',
        'BILLING_MEDIA_SERVICE_URL': 'media', 'BILLING_USER_SERVICE_URL': 'user',
        'STORAGE_IAM_SERVICE_URL': 'iam', 'ROUTING_IAM_SERVICE_URL': 'iam',
        'CANVAS_IAM_SERVICE_URL': 'iam', 'OPERATIONS_IAM_SERVICE_URL': 'iam',
        'PROMOTION_IAM_SERVICE_URL': 'iam',
    }.items():
        common[key] = f'http://{service}:{PORTS[service]}'
    for service in PORTS:
        env = dict(common)
        if service != 'gateway':
            prefix = service.upper() + '_DB_'
            env.update({prefix + 'URL': f'jdbc:mysql://mysql:3306/neibu_{service}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=utf8',
                        prefix + 'USERNAME': 'neibu', prefix + 'PASSWORD': private['DB_PASSWORD']})
        if service != 'iam':
            env.pop('SPRING_PROFILES_ACTIVE')
        (STATE / 'env' / (service + '.env')).write_text('\n'.join(key + '=' + value for key, value in env.items()) + '\n', encoding='utf-8')
        if service == 'gateway':
            jar = REPO / 'gateways/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar'
        else:
            directory = REPO / f'services/{service}-service/{service}-bootstrap/target'
            jars = list(directory.glob('*-exec.jar')) or [p for p in directory.glob('*.jar') if not p.name.endswith(('-sources.jar', '-javadoc.jar'))]
            if len(jars) != 1:
                raise RuntimeError('Build the backend first; executable JAR is ambiguous or absent: ' + service)
            jar = jars[0]
        # Existing bind mounts keep their open JAR inode intact until recreation.
        destination = STATE / 'jars' / (service + '.jar')
        pending_jar = destination.with_suffix('.jar.next')
        shutil.copy2(jar, pending_jar)
        pending_jar.replace(destination)
    nginx = (REPO / 'deployment/local/nginx.conf').read_text(encoding='utf-8')
    (STATE / 'nginx.conf').write_text(nginx.replace('host.docker.internal:8200', 'gateway:8200'), encoding='utf-8')
    labels = {'cn.neibu.environment': 'neibu 本地环境', 'cn.neibu.workspace': str(REPO)}
    services = {
        'mysql': {
            'image': 'mysql:8.0.46', 'restart': 'unless-stopped', 'labels': labels,
            'environment': {'MYSQL_ROOT_PASSWORD': '${MYSQL_ROOT_PASSWORD}', 'TZ': 'Asia/Shanghai'},
            'command': ['--character-set-server=utf8mb4', '--collation-server=utf8mb4_0900_ai_ci', '--lower-case-table-names=1', '--innodb-buffer-pool-size=256M'],
            'ports': ['127.0.0.1:23326:3306'], 'volumes': ['mysql-data:/var/lib/mysql', './target/mysql-init:/docker-entrypoint-initdb.d:ro'],
            'healthcheck': {'test': ['CMD-SHELL', 'MYSQL_PWD="$$MYSQL_ROOT_PASSWORD" mysql -uroot -Nse "SELECT 1"'], 'interval': '5s', 'timeout': '5s', 'retries': 40},
        },
        'redis': {
            'image': 'redis:7.0.15-alpine', 'restart': 'unless-stopped', 'labels': labels,
            'environment': {'REDIS_PASSWORD': '${REDIS_PASSWORD}'},
            'command': ['sh', '-c', 'exec redis-server --appendonly yes --maxmemory 128mb --maxmemory-policy allkeys-lfu --requirepass "$$REDIS_PASSWORD"'],
            'ports': ['127.0.0.1:26399:6379'], 'volumes': ['redis-data:/data'],
            'healthcheck': {'test': ['CMD-SHELL', 'REDISCLI_AUTH="$$REDIS_PASSWORD" redis-cli ping'], 'interval': '5s', 'timeout': '5s', 'retries': 20},
        },
    }
    for service, port in PORTS.items():
        if service in ('canvas', 'operations'):
            path = '/api/v1/canvases' if service == 'canvas' else '/api/v1/admin/operations/users'
            probe = ['CMD', '/bin/bash', '-ec',
                     f'test "$(curl -s -o /dev/null -w "%{{http_code}}" --max-time 5 http://127.0.0.1:{port}{path})" = 401']
        else:
            path = '/api/v1/auth/session' if service == 'iam' else '/actuator/health'
            probe = ['CMD', 'curl', '-fsS', '--max-time', '5', f'http://127.0.0.1:{port}{path}']
        services[service] = {
            'image': 'eclipse-temurin:21-jre', 'restart': 'unless-stopped', 'init': True, 'labels': labels,
            'env_file': ['./target/env/' + service + '.env'],
            'command': ['java', '-jar', '/app/service.jar'], 'volumes': ['./target/jars/' + service + '.jar:/app/service.jar:ro'],
            'depends_on': {'mysql': {'condition': 'service_healthy'}, 'redis': {'condition': 'service_healthy'}},
            'mem_limit': '768m', 'healthcheck': {'test': probe,
                'interval': '10s', 'timeout': '3s', 'start_period': '60s', 'retries': 30},
        }
    services['storage']['image'] = 'neibu-storage:java21'
    services['storage']['build'] = {'context': '.', 'dockerfile': 'storage.Dockerfile'}
    services['gateway']['ports'] = ['127.0.0.1:18300:8200']
    services['gateway']['depends_on'].update({name: {'condition': 'service_healthy'} for name in PORTS if name != 'gateway'})
    services['web'] = {
        'container_name': 'neibu', 'image': 'nginx:1.24.0-alpine', 'restart': 'unless-stopped', 'labels': labels,
        'depends_on': {'gateway': {'condition': 'service_healthy'}},
        'ports': ['127.0.0.1:18390:8080', '127.0.0.1:18392:8082'],
        'volumes': ['./target/nginx.conf:/etc/nginx/conf.d/default.conf:ro',
            '../../frontends/user-web/dist:/srv/user:ro', '../../frontends/admin-web/dist:/srv/admin:ro',
            '../../frontends/partner-web/dist:/srv/partner:ro'],
        'healthcheck': {'test': ['CMD-SHELL', 'wget -q -O /dev/null http://127.0.0.1:8080/'], 'interval': '10s', 'timeout': '5s', 'retries': 10},
    }
    write_json(STATE / 'compose.json', {'name': 'neibu', 'services': services,
        'volumes': {'mysql-data': {'labels': labels}, 'redis-data': {'labels': labels}}})
    (STATE / '.env.credentials.txt').write_text(
        'Administrator: ' + private['IAM_BOOTSTRAP_ADMIN_USERNAME'] + '\n'
        'Password: ' + private['IAM_BOOTSTRAP_ADMIN_PASSWORD'] + '\n'
        'Test phone: 19900000001\nLocal OTP: 123456\n'
        'MySQL: 127.0.0.1:23326 / neibu / ' + private['DB_PASSWORD'] + '\n'
        'Redis: 127.0.0.1:26399 / ' + private['REDIS_PASSWORD'] + '\n', encoding='utf-8')
    print('Prepared neibu 本地环境: 15 containers, 11 fresh schemas, independent credentials and volumes.')


if __name__ == '__main__':
    prepare()
