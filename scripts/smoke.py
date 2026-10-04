#!/usr/bin/env python3
"""Check container/deployment startup and the basic lifecycle; optionally check a DB outage."""
import argparse
import json
import time
import urllib.error
import urllib.request
import uuid

from mint_token import token

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('base_url')
parser.add_argument('--expect-db-down', action='store_true')
args = parser.parse_args()
base = args.base_url.rstrip('/')


def request(method, path, bearer=None, body=None, key=None):
    headers = {}
    if bearer:
        headers['Authorization'] = 'Bearer ' + bearer
    if key:
        headers['Idempotency-Key'] = key
    if body is not None:
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(base + path, method=method, headers=headers,
                                 data=None if body is None else json.dumps(body).encode())
    try:
        response = urllib.request.urlopen(req, timeout=90)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        text = response.read().decode()
        try:
            result = json.loads(text)
        except ValueError:
            result = text
        return response.status, result


def check(condition, message):
    if not condition:
        raise SystemExit('FAIL: ' + message)


if args.expect_db_down:
    check(request('GET', '/actuator/health/liveness')[0] == 200, 'Liveness must remain up during DB outage')
    check(request('GET', '/actuator/health/readiness')[0] == 503, 'Readiness must fail with DB down')
    check(request('GET', '/metrics')[0] == 503, 'Business metrics must fail rather than return stale inventory')
    check(request('POST', '/shows', token('smoke-admin', True),
                  {'name': 'must-fail', 'seats': ['A1'], 'price_paise': 100})[0] == 503,
          'Writes must fail closed with DB down')
    print('PASS: liveness stays up; readiness, metrics, and writes fail closed during DB outage.')
else:
    deadline = time.monotonic() + 120
    while True:
        try:
            if request('GET', '/actuator/health/readiness')[0] == 200:
                break
        except (urllib.error.URLError, TimeoutError, OSError):
            pass
        check(time.monotonic() < deadline, 'Service did not become ready within startup deadline')
        time.sleep(2)
    check(request('GET', '/actuator/health/liveness')[0] == 200, 'Liveness')
    suffix = str(uuid.uuid4())
    admin, alice, bob = token('smoke-admin', True), token('alice-' + suffix), token('bob-' + suffix)
    status, show = request('POST', '/shows', admin,
                           {'name': 'smoke-' + suffix, 'seats': ['A1', 'A2'], 'price_paise': 25000})
    check(status == 201, f'Show creation: {status} {show}')
    path = '/shows/' + show['id']
    status, reservation = request('POST', path + '/reserve', alice, {'seats': ['A1']}, 'smoke')
    check(status == 201, f'Reservation creation: {status} {reservation}')
    status, retry = request('POST', path + '/reserve', alice, {'seats': ['A1']}, 'smoke')
    check(status == 200 and retry == reservation, 'Idempotency replay')
    status, conflict = request('POST', path + '/reserve', bob, {'seats': ['A1']}, 'bob')
    check(status == 409 and conflict['code'] == 'SEAT_TAKEN', 'Seat conflict')
    cancel_path = '/reservations/' + reservation['reservation_id'] + '/cancel'
    check(request('POST', cancel_path, bob)[0] == 404, 'Owner-only cancellation')
    check(request('POST', cancel_path, alice)[0] == 200, 'Cancellation')
    check(request('POST', path + '/reserve', bob, {'seats': ['A1']}, 'bob-fresh')[0] == 201, 'Rebooking')
    check(request('POST', cancel_path, alice)[0] == 200, 'Repeated old cancellation')
    status, state = request('GET', path)
    check(status == 200 and state['counts'] == {'available': 1, 'held': 0, 'confirmed': 1, 'total_seats': 2},
          'Final reconciliation')
    status, metrics = request('GET', '/metrics')
    check(status == 200 and f'seats{{show_id="{show["id"]}",state="confirmed"}} 1' in metrics, 'Metrics reconciliation')
    print('PASS: startup, authentication, booking, replay, conflict, cancellation, rebooking, reconciliation, metrics.')
