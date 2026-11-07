"""Measure eth0 IP traffic by socket-owning service; retain no payloads."""
import collections
import datetime
import ipaddress
import json
from pathlib import Path
import re
import socket
import struct
import subprocess
import threading
import time

LOCAL = '172.31.218.45'
DURATION = 30
flows, listeners, names = {}, {}, {}
stop = threading.Event()


def endpoint(value):
    host, port = value.rsplit(':', 1)
    return host.strip('[]').replace('::ffff:', ''), int(port) if port.isdigit() else 0


def refresh():
    while not stop.is_set():
        result = subprocess.run(['ss', '-Htunap'], capture_output=True, text=True)
        for line in result.stdout.splitlines():
            parts = line.split()
            if len(parts) < 6 or parts[0] not in ('tcp', 'udp'):
                continue
            try:
                a, ap = endpoint(parts[4])
                b, bp = endpoint(parts[5])
            except ValueError:
                continue
            owners = re.findall(r'\("([^\"]+)",pid=(\d+)', line)
            ids = tuple(sorted({int(pid) for _, pid in owners}))
            if not ids:
                continue
            for name, pid in owners:
                names[int(pid)] = name
            if parts[1] == 'LISTEN' or (parts[0] == 'udp' and bp == 0):
                listeners[(parts[0], ap)] = ids
            else:
                flows[(parts[0], a, ap, b, bp)] = ids
        stop.wait(0.5)


def net():
    line = next(line for line in Path('/proc/net/dev').read_text().splitlines() if 'eth0:' in line)
    values = line.split(':')[1].split()
    return {'rx': int(values[0]), 'tx': int(values[8])}


thread = threading.Thread(target=refresh, daemon=True)
thread.start()
time.sleep(0.8)
# ETH_P_ALL is required to observe outgoing packets as well as incoming ones.
sock = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.htons(0x0003))
sock.bind(('eth0', 0))
sock.settimeout(0.2)
counts = collections.defaultdict(lambda: [0, 0])
totals = collections.Counter()
start = net()
started = datetime.datetime.now().astimezone().isoformat()
begin = time.monotonic()
while time.monotonic() - begin < DURATION:
    try:
        data, address = sock.recvfrom(96)
    except socket.timeout:
        continue
    if len(data) < 38 or data[12:14] != b'\x08\x00':
        continue
    ihl = (data[14] & 15) * 4
    proto = {6: 'tcp', 17: 'udp'}.get(data[23], 'other')
    length = struct.unpack('!H', data[16:18])[0]
    src, dst = socket.inet_ntoa(data[26:30]), socket.inet_ntoa(data[30:34])
    sp = dp = 0
    if proto in ('tcp', 'udp') and len(data) >= 18 + ihl:
        sp, dp = struct.unpack('!HH', data[14 + ihl:18 + ihl])
    if src == LOCAL:
        direction, lp, peer, port = 'tx', sp, dst, dp
    elif dst == LOCAL:
        direction, lp, peer, port = 'rx', dp, src, sp
    else:
        continue
    counts[(proto, lp, peer, port)][0 if direction == 'tx' else 1] += length
    totals[direction] += length
elapsed = time.monotonic() - begin
end = net()
sock.close()
stop.set()
thread.join(timeout=2)
services = {}
for p in Path('/proc').iterdir():
    if not p.name.isdigit():
        continue
    try:
        match = re.search(r'/([^/]+\.service)(?:/|\n|$)', (p / 'cgroup').read_text())
        if match:
            services[int(p.name)] = match[1]
    except OSError:
        pass
summary = collections.defaultdict(lambda: [0, 0, set()])
connections = []
for (proto, lp, peer, port), values in counts.items():
    pids = flows.get((proto, LOCAL, lp, peer, port), listeners.get((proto, lp), ()))
    service = ' / '.join(sorted({services.get(pid, names.get(pid, str(pid))) for pid in pids})) or 'unattributed'
    scope = 'private' if ipaddress.ip_address(peer).is_private or peer.startswith('100.100.') else 'public'
    row = summary[(service, scope)]
    row[0] += values[0]
    row[1] += values[1]
    row[2].add(peer)
    connections.append({'service': service, 'pids': pids, 'scope': scope, 'peer': peer,
                        'local_port': lp, 'peer_port': port, 'protocol': proto,
                        'tx_kib_s': round(values[0] / elapsed / 1024, 2),
                        'rx_kib_s': round(values[1] / elapsed / 1024, 2)})
rows = [{'service': service, 'scope': scope, 'tx_kib_s': round(v[0] / elapsed / 1024, 2),
         'rx_kib_s': round(v[1] / elapsed / 1024, 2), 'peer_count': len(v[2])}
        for (service, scope), v in summary.items()]
print(json.dumps({'started_cst': started, 'elapsed_seconds': round(elapsed, 2),
                  'method': 'eth0 IPv4 header lengths including retransmissions; sockets mapped every 0.5 seconds; no payload retained',
                  'interface_mbps': {k: round((end[k] - start[k]) * 8 / elapsed / 1e6, 4) for k in start},
                  'captured_ipv4_mbps': {k: round(totals[k] * 8 / elapsed / 1e6, 4) for k in ('tx', 'rx')},
                  'services': sorted(rows, key=lambda r: r['tx_kib_s'] + r['rx_kib_s'], reverse=True),
                  'connections': sorted(connections, key=lambda r: r['tx_kib_s'] + r['rx_kib_s'], reverse=True)[:15]}, indent=2))
