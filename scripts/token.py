#!/usr/bin/env python3
"""Operator-only token minting. Keep JWT_SECRET private; share only generated tokens."""
import argparse
import base64
import hashlib
import hmac
import json
import os
import time
from pathlib import Path


def secret():
    value = os.environ.get('JWT_SECRET')
    if not value:
        env = Path(__file__).resolve().parent.parent / '.env'
        if env.exists():
            values = dict(line.split('=', 1) for line in env.read_text().splitlines() if '=' in line)
            value = values.get('JWT_SECRET')
    if not value or len(value.encode()) < 32:
        raise SystemExit('Set JWT_SECRET or run scripts/setup-env.sh first.')
    return value


def token(user, admin=False, ttl=3600):
    def encode(value):
        return base64.urlsafe_b64encode(value).rstrip(b'=')
    now = int(time.time())
    claims = {'sub': user, 'iss': 'seat-reservation', 'aud': ['seat-api'],
              'iat': now, 'exp': now + ttl, 'scope': 'admin' if admin else 'book'}
    parts = [encode(json.dumps({'alg': 'HS256', 'typ': 'JWT'}).encode()),
             encode(json.dumps(claims).encode())]
    payload = b'.'.join(parts)
    return (payload + b'.' + encode(hmac.new(secret().encode(), payload, hashlib.sha256).digest())).decode()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('user')
    parser.add_argument('--admin', action='store_true')
    parser.add_argument('--ttl', type=int, default=3600)
    args = parser.parse_args()
    print(token(args.user, args.admin, args.ttl))
