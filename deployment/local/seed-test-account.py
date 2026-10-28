"""Open the existing local OTP test account through audited manual recharge APIs.

Only localhost and xzwl_local_* databases are accepted. Repeating this script
reuses the same recharge rather than adding more points. No model generation.
"""
import http.cookiejar
import json
import time
import urllib.error
import urllib.request
from decimal import Decimal, ROUND_CEILING
import runtime

BASE = 'http://127.0.0.1:18090'
PHONE = '19900000001'
STATE = runtime.STATE / 'test-account.json'
REMARK = '本地开发测试积分；模拟手工充值，无真实付款。'


def main():
    envs = runtime.read_json(runtime.STATE / '.env.services.json')
    for service in ('iam', 'user', 'access', 'payment', 'billing'):
        expected = 'jdbc:mysql://127.0.0.1:23306/xzwl_local_' + service + '?'
        if not envs[service].get(service.upper() + '_DB_URL', '').startswith(expected):
            raise RuntimeError('Local database guard failed: ' + service)
    if envs['payment'].get('PAYMENT_PROVIDER') != 'disabled':
        raise RuntimeError('Online payment provider must remain disabled')
    rows = runtime.mysql('SELECT user_id FROM xzwl_local_user.user_identity WHERE enabled=1 AND phone IN ('
                         + runtime.literal(PHONE) + ',' + runtime.literal('+86' + PHONE) + ');')
    if len(rows) != 1:
        raise RuntimeError('Expected one existing local OTP test user')
    owner = rows[0]
    cookies = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(cookies))

    def call(path, data=None, csrf=None):
        headers = {'Content-Type': 'application/json'}
        if csrf:
            headers['X-CSRF-TOKEN'] = csrf
        req = urllib.request.Request(BASE + '/admin/api/v1/' + path,
                                     data=json.dumps(data).encode() if data is not None else None, headers=headers)
        try:
            with client.open(req, timeout=15) as response:
                if response.geturl() != req.full_url:
                    raise RuntimeError('Unexpected redirect')
                return json.load(response)
        except urllib.error.HTTPError as exc:
            raise RuntimeError('Local API HTTP ' + str(exc.code) + ': ' + path) from None

    credentials = runtime.read_json(runtime.STATE / '.env.runtime.json')
    session = call('auth/session')
    call('auth/admin/login', {'username': credentials['IAM_BOOTSTRAP_ADMIN_USERNAME'],
                             'password': credentials['IAM_BOOTSTRAP_ADMIN_PASSWORD']}, session['csrfToken'])
    session = call('auth/session')
    if not session['authenticated'] or 'admin' not in {r.lower() for r in session['roles']}:
        raise RuntimeError('Local administrator login failed')
    try:
        options = call('admin/payments/recharge-options?userId=' + owner)
        if options['accountType'] != 'PERSONAL':
            raise RuntimeError('Expected the existing PERSONAL test account')
        if STATE.exists():
            seed = runtime.read_json(STATE)
            if seed['userId'] != owner or seed['phone'] != PHONE:
                raise RuntimeError('Seed ownership changed')
        else:
            rate = Decimal(options['pointsPerYuan4'])
            if rate <= 0:
                raise RuntimeError('Invalid local recharge conversion')
            amount = (Decimal('10000') / rate).quantize(Decimal('.01'), rounding=ROUND_CEILING)
            rule = next(r for r in options['rules'] if r['accountType'] == 'PERSONAL')
            amount = max(amount, Decimal(rule['minimumAmount4']))
            seed = {'phone': PHONE, 'userId': owner, 'requestId': 'local-test-opening-v1-' + owner,
                    'amount4': format(amount, '.4f')}
            runtime.write_json(STATE, seed)
        order = call('admin/payments/recharges', {'userId': owner, 'requestId': seed['requestId'],
                     'amount4': seed['amount4'], 'accountType': 'PERSONAL', 'enterpriseName': None,
                     'remark': REMARK}, session['csrfToken'])
        seed['orderId'] = order['orderId']
        runtime.write_json(STATE, seed)
        call('admin/payments/recharges/' + order['orderId'] + '/confirm', {}, session['csrfToken'])
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            orders = call('admin/payments/recharges?userId=' + owner)
            settled = next(o for o in orders if o['orderId'] == seed['orderId'])
            if settled['status'] == 'PAID' and settled['fulfilled']:
                break
            time.sleep(1)
        else:
            raise RuntimeError('Local recharge not fulfilled; inspect payment worker logs and rerun safely')
        key_rows = runtime.mysql('SELECT key_id FROM xzwl_local_access.access_key WHERE owner_id='
                                 + runtime.literal(owner) + ' AND enabled=1 AND paid_order_id='
                                 + runtime.literal(seed['orderId']) + ';')
        if len(key_rows) != 1:
            raise RuntimeError('First recharge and primary key must be linked')
        # This is a read-only endpoint shared by user and administrator roles.
        wallet = call('billing/wallets/' + key_rows[0])
        seed.update(fulfilled=True, expectedPoints4=settled['expectedPoints4'],
                    availablePoints4=wallet['availablePoints4'], onlinePayment=False)
        runtime.write_json(STATE, seed)
        print('PASS local test account opening, first recharge and wallet linkage', flush=True)
        print('Available test points: ' + wallet['availablePoints4'], flush=True)
    finally:
        call('auth/logout', {}, session['csrfToken'])


if __name__ == '__main__':
    main()
