#!/usr/bin/env python3
"""Exercise HTTP concurrency and fail the process if any correctness check fails."""
import argparse
import asyncio
from collections import Counter
import importlib.util
import json
import os
from pathlib import Path
import time
import uuid

import aiohttp


def arguments():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('base_url')
    p.add_argument('--requests', type=int, default=20000)
    p.add_argument('--concurrency', type=int, default=20000)
    p.add_argument('--tokens-file', help='JSON with admin_token and user_tokens; no signing secret needed')
    args = p.parse_args()
    if args.requests < 10 or args.concurrency < 1:
        p.error('--requests must be >= 10 and --concurrency must be positive')
    return args


def credentials(args):
    if args.tokens_file:
        data = json.loads(Path(args.tokens_file).read_text())
        if len(data['user_tokens']) < args.requests:
            raise SystemExit('The token bundle must have at least --requests distinct user tokens.')
        return data['admin_token'], data['user_tokens']
    spec = importlib.util.spec_from_file_location('seat_tokens', Path(__file__).with_name('mint_token.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    prefix = str(uuid.uuid4())
    return module.token('burst-admin', True), [module.token(f'{prefix}-{i}') for i in range(args.requests)]


async def run(args):
    admin, users = credentials(args)
    base = args.base_url.rstrip('/')
    totals = Counter()
    checks = []
    semaphore = asyncio.Semaphore(args.concurrency)
    # Leave some connections for reconciliation reads while the storm is running.
    connector = aiohttp.TCPConnector(limit=args.concurrency + 10)
    timeout = aiohttp.ClientTimeout(total=180)
    async with aiohttp.ClientSession(connector=connector, timeout=timeout) as client:
        async def request(method, path, token=None, payload=None, key=None):
            headers = {}
            if token:
                headers['Authorization'] = 'Bearer ' + token
            if key:
                headers['Idempotency-Key'] = key
            try:
                async with client.request(method, base + path, json=payload, headers=headers) as r:
                    text = await r.text()
                    try:
                        body = json.loads(text)
                    except ValueError:
                        body = {'raw': text[:200]}
                    return r.status, body, dict(r.headers)
            except (aiohttp.ClientError, asyncio.TimeoutError, OSError) as e:
                return 0, {'code': type(e).__name__, 'message': str(e)}, {}

        def classify(result):
            status, body, _ = result
            if status == 201:
                return 'confirmed'
            if status == 200:
                return 'replayed'
            if status >= 500:
                return '5xx'
            if status == 0:
                return 'transport_error'
            return body.get('code', str(status))

        async def show(count, limit=4):
            r = await request('POST', '/shows', admin, {
                'name': 'burst-' + str(uuid.uuid4()), 'seats': [f'A{i}' for i in range(1, count + 1)],
                'price_paise': 25000, 'per_user_limit': limit})
            if r[0] != 201:
                raise RuntimeError(f'Show creation failed: {r[:2]}')
            return r[1]['id']

        async def reserve(show_id, user_index, seats, key):
            async with semaphore:
                result = await request('POST', f'/shows/{show_id}/reserve', users[user_index], {'seats': seats}, key)
                totals[classify(result)] += 1
                return result

        def reconcile(state):
            counts = state['counts']
            actual = Counter(seat['status'] for seat in state['seats'])
            return (sum(counts[k] for k in ('available', 'held', 'confirmed')) == counts['total_seats']
                    == len(state['seats']) and all(actual[k] == counts[k] for k in ('available', 'held', 'confirmed')))

        async def final_state(show_id, expected):
            result = await request('GET', '/shows/' + show_id)
            valid = result[0] == 200 and reconcile(result[1]) and result[1]['counts']['confirmed'] == expected
            checks.append(valid)
            print(json.dumps({'show_id': show_id, 'counts': result[1].get('counts'), 'reconciles': valid}))

        healthy = await request('GET', '/actuator/health/readiness')
        if healthy[0] != 200:
            raise RuntimeError(f'Service is not ready: {healthy[:2]}')

        hot = await show(2)
        stop = asyncio.Event()
        samples = []

        async def observe():
            while not stop.is_set():
                state = await request('GET', '/shows/' + hot)
                samples.append(state[0] == 200 and reconcile(state[1]))
                await asyncio.sleep(0.05)

        observer = asyncio.create_task(observe())
        started = time.monotonic()
        results = await asyncio.gather(*(reserve(hot, i, ['A1'], f'hot-{hot}-{i}') for i in range(args.requests)))
        stop.set()
        observer.cancel()
        try:
            await observer
        except asyncio.CancelledError:
            pass
        distribution = Counter(classify(r) for r in results)
        checks.append(distribution == Counter({'confirmed': 1, 'SEAT_TAKEN': args.requests - 1}))
        checks.append(bool(samples) and all(samples))
        print(json.dumps({'scenario': 'hot-seat', 'requests': args.requests, 'concurrency': args.concurrency,
                          'seconds': round(time.monotonic() - started, 3), 'outcomes': distribution,
                          'reconciliation_samples': len(samples), 'all_samples_valid': all(samples),
                          'transport_errors': dict(Counter(r[1].get('code') for r in results if r[0] == 0)),
                          'transport_error_examples': [r[1] for r in results if r[0] == 0][:3]}))
        await final_state(hot, 1)

        replay_show = await show(3)
        replay_key = 'retry-' + replay_show
        retries = await asyncio.gather(*(reserve(replay_show, 0, ['A1', 'A2'] if i % 2 else ['A2', 'A1'], replay_key)
                                         for i in range(100)))
        checks.append(Counter(classify(r) for r in retries) == Counter({'confirmed': 1, 'replayed': 99}))
        checks.append(len({r[1].get('reservation_id') for r in retries}) == 1)
        conflict = await reserve(replay_show, 0, ['A3'], replay_key)
        checks.append(conflict[0] == 409 and conflict[1].get('code') == 'IDEMPOTENCY_KEY_REUSED')
        await final_state(replay_show, 2)

        limit_show = await show(10)
        limited = await asyncio.gather(*(reserve(limit_show, 1, [f'A{i+1}'], f'limit-{limit_show}-{i}') for i in range(10)))
        checks.append(Counter(classify(r) for r in limited) == Counter({'confirmed': 4, 'PER_USER_LIMIT': 6}))
        await final_state(limit_show, 4)

        partial = await show(2)
        original = await reserve(partial, 2, ['A1'], 'owner-' + partial)
        declined = await reserve(partial, 3, ['A1', 'A2'], 'partial-' + partial)
        checks.append(declined[0] == 409)
        await final_state(partial, 1)
        if original[0] == 201:
            path = '/reservations/' + original[1]['reservation_id'] + '/cancel'
            unauthorized = await request('POST', path, users[3])
            checks.append(unauthorized[0] == 404)
            cancelled = await request('POST', path, users[2])
            checks.append(cancelled[0] == 200)
            rebooked = await reserve(partial, 3, ['A1', 'A2'], 'fresh-' + partial)
            checks.append(rebooked[0] == 201)
            repeated = await request('POST', path, users[2])  # must not clear the new owner
            checks.append(repeated[0] == 200)
            await final_state(partial, 2)
        else:
            checks.append(False)

        spoof = await request('POST', '/shows/' + hot + '/reserve', users[4],
                              {'seats': ['A2'], 'user_id': 'victim'}, 'spoof-' + hot)
        checks.append(spoof[0] == 400)
        await final_state(hot, 1)

        async with client.get(base + '/metrics') as metrics:
            text = await metrics.text()
            checks.append(metrics.status == 200 and f'seats{{show_id="{hot}",state="confirmed"}} 1' in text)

    passed = all(checks) and totals['5xx'] == 0 and totals['transport_error'] == 0
    print(json.dumps({'result': 'PASS' if passed else 'FAIL', 'reservation_outcomes': totals,
                      'checks_passed': sum(checks), 'checks_total': len(checks)}))
    return 0 if passed else 1


if __name__ == '__main__':
    args = arguments()
    try:
        import resource
        soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
        desired = args.concurrency + 256
        if soft < desired:
            resource.setrlimit(resource.RLIMIT_NOFILE, (min(desired, hard), hard))
    except (ImportError, ValueError, OSError):
        pass
    try:
        raise SystemExit(asyncio.run(run(args)))
    except (RuntimeError, aiohttp.ClientError, asyncio.TimeoutError) as e:
        print(json.dumps({'result': 'FAIL', 'error': str(e)}))
        raise SystemExit(1)
