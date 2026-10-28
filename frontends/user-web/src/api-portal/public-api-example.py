#!/usr/bin/env python3
"""Python 3 standard library client; XZWL_BASE_URL is the Gateway, XZWL_API_KEY is a platform Key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--type', choices=['IMAGE', 'VIDEO'], default='IMAGE')
    parser.add_argument('--count', type=int, default=1)
    parser.add_argument('--prompt', default='雨后的森林，阳光透过树叶')
    parser.add_argument('--request-file', default='xzwl-request.json', help='保存并复用同一任务请求；新任务使用新文件')
    parser.add_argument('--task', help='只查询此任务，不创建新任务')
    parser.add_argument('--reference-file', help='可选 JPG/PNG 文件，先上传再传 fileId；不接受 Base64')
    args = parser.parse_args()
    base = os.environ['XZWL_BASE_URL'].rstrip('/')
    key = os.environ['XZWL_API_KEY']
    fingerprint = hashlib.sha256(key.encode()).hexdigest()

    def call(path, data=None, request_id=None, content_type='application/json'):
        headers = {'Authorization': 'Bearer ' + key}
        if data is not None:
            headers['Content-Type'] = content_type
        if request_id:
            headers['Idempotency-Key'] = request_id
        payload = data if isinstance(data, bytes) else None if data is None else json.dumps(data).encode()
        try:
            with urlopen(Request(base + path, headers=headers, data=payload), timeout=30) as response:
                return json.load(response)
        except HTTPError as error:
            # Never print request headers or secrets. Retain the saved request for a safe retry.
            raise SystemExit(f'HTTP {error.code}；检查 Key、权限或参数，重试必须复用请求文件。') from None

    if args.task:
        task_id = args.task
    else:
        request_file = Path(args.request_file)
        if request_file.exists():
            saved = json.loads(request_file.read_text())
            if saved.get('gateway') != base or saved.get('keyFingerprint') != fingerprint:
                raise SystemExit('请求文件属于其他 Gateway 或 Key；新任务须使用新文件并重新确认费用。')
            print('复用已保存请求；本次 --type/--count/--prompt/--reference-file 不改变原任务。')
        else:
            models = call('/api/v1/media/generation-options')['models']
            model = next((item for item in models if item['type'] == args.type), None)
            if model is None:
                raise SystemExit('暂无可用模型，请配置 API 价格与模型路由。')
            if not 1 <= args.count <= model['maxCount']:
                raise SystemExit('count 超过模型允许范围。')
            selection = dict(type=args.type, model=model['model'], aspectRatio=model['aspectRatios'][0],
                             resolution=model['resolutions'][0], count=args.count,
                             durationSeconds=model['minDurationSeconds'] if args.type == 'VIDEO' else 0)
            print('预计消耗积分：', call('/api/v1/media/quotes', selection)['points'])
            if input('确认提交？输入 yes：') != 'yes':
                return
            references = []
            if args.reference_file:
                file = Path(args.reference_file)
                mime = {'.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg'}.get(file.suffix.lower())
                if not mime or not 0 < file.stat().st_size <= 20 * 1024 * 1024:
                    raise SystemExit('参考图必须为不超过 20 MiB 的 JPG/PNG。')
                boundary = uuid.uuid4().hex
                data = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="reference{file.suffix.lower()}"\r\n'
                        f'Content-Type: {mime}\r\n\r\n').encode() + file.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
                uploaded = call('/api/v1/files/reference-images', data, content_type='multipart/form-data; boundary=' + boundary)
                references = [{'fileId': uploaded['fileId']}]
            saved = {'gateway': base, 'keyFingerprint': fingerprint, 'requestId': str(uuid.uuid4()), 'body': {**selection, 'prompt': args.prompt, 'references': references}}
            # Persist before submission; exclusive creation avoids overwriting another request.
            with request_file.open('x') as output:
                json.dump(saved, output, ensure_ascii=False, indent=2)
        task_id = call('/api/v1/media/tasks', saved['body'], saved['requestId'])['id']
        print('任务 ID：', task_id, '；后续可用 --task 查询。')
    for _ in range(180):
        task = call('/api/v1/media/tasks/' + task_id)
        if task['status'] in ('SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED', 'TIMED_OUT'):
            break
        time.sleep(5)
    print('当前状态：', task['status'])
    for result in call('/api/v1/media/tasks/' + task_id + '/results'):
        file = call('/api/v1/files/' + result['fileId'] + '/access-url')
        print('结果：', file['url'], '有效期：', file['expiresAt'])
    # REFUND_PENDING is not terminal. Requery the same task; never create a replacement to poll it.


if __name__ == '__main__':
    main()
