"""Read-only exporter. Invoked through SSH; never modifies a remote file or DB."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys

SERVICES = ('gateway', 'iam', 'user', 'access', 'promotion', 'billing', 'payment',
            'routing', 'media', 'canvas', 'storage', 'operations')
TABLES = {
    'media': ('media_provider_connection', 'media_ark_connection', 'media_mps_configuration'),
    'routing': ('routing_provider', 'routing_model_config'),
    'billing': ('media_price_catalog', 'media_price', 'recharge_conversion_rate'),
    'promotion': ('promotion_benefit_policy',),
    'payment': ('payment_provider_state',),
}

def mysql(sql):
    result = subprocess.run(['sudo', 'mysql', '--batch', '--raw', '--skip-column-names',
                             '--default-character-set=utf8mb4'], input=sql, text=True,
                            capture_output=True, check=True)
    return [line for line in result.stdout.splitlines() if line]

if sys.argv[1] == 'environment':
    result = {}
    for service in SERVICES:
        pid = subprocess.run(['systemctl', 'show', 'xzwl-' + service + '.service',
                              '--property=MainPID', '--value'], text=True,
                             capture_output=True, check=True).stdout.strip()
        if pid == '0':
            continue
        entries = Path('/proc/' + pid + '/environ').read_bytes().decode().split('\0')
        env = dict(entry.split('=', 1) for entry in entries if '=' in entry)
        prefixes = ('SPRING_', 'NACOS_', 'SERVICE_', 'SERVER_', 'XZWL_', 'ABP_', 'IAM_',
                    'USER_', 'ACCESS_', 'BILLING_', 'PAYMENT_', 'PROMOTION_', 'ROUTING_',
                    'MEDIA_', 'STORAGE_', 'OPERATIONS_', 'CANVAS_', 'GATEWAY_', 'TOS_',
                    'VOLC_', 'TENCENTCLOUD_', 'REDIS_', 'SQB_')
        result[service] = {k: v for k, v in env.items() if k.startswith(prefixes)}
    print(json.dumps({'release': os.path.realpath('/opt/xzwl/current'),
                      'services': result}, ensure_ascii=False))
elif sys.argv[1] == 'database':
    tables = {}
    for service, names in TABLES.items():
        schema = service + '_db'
        for table in names:
            columns = mysql("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='"
                            + schema + "' AND TABLE_NAME='" + table + "' ORDER BY ORDINAL_POSITION;")
            if not columns or any(not re.fullmatch(r'[A-Za-z0-9_]+', c) for c in columns):
                raise RuntimeError('Missing or invalid configuration table: ' + table)
            pairs = ','.join("'" + c + "',CAST(`" + c + "` AS CHAR CHARACTER SET utf8mb4)" for c in columns)
            where = " WHERE id LIKE 'terminal:%'" if table == 'payment_provider_state' else ''
            rows = [json.loads(x) for x in mysql('SELECT JSON_OBJECT(' + pairs + ') FROM `' + schema + '`.`' + table + '`' + where + ';')]
            tables[service + '.' + table] = {'columns': columns, 'rows': rows}
    configs = [json.loads(x) for x in mysql("SELECT JSON_OBJECT('dataId',data_id,'group',group_id,'tenant',tenant_id,'content',content,'type',type) FROM nacos_db.config_info WHERE tenant_id='xzwl-prod' AND group_id='XZWL';")]
    expected = {'xzwl-common.yaml'} | {s + '-service.yaml' for s in SERVICES if s not in ('canvas', 'operations')}
    configs = [c for c in configs if c['dataId'] in expected]
    if {c['dataId'] for c in configs} != expected:
        raise RuntimeError('Production Nacos configuration inventory mismatch')
    print(json.dumps({'source': 'aiyomio.cn production / 146 + 62 + 124',
                      'tables': tables, 'nacos': configs,
                      'nacosSchema': Path('/opt/nacos-2.5.4/conf/mysql-schema.sql').read_text()}, ensure_ascii=False))
else:
    raise RuntimeError('Unknown read-only export mode')
