#!/usr/bin/env python3
"""Generate short-lived evaluator credentials without sharing the signing secret."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import uuid

spec = importlib.util.spec_from_file_location('seat_tokens', Path(__file__).with_name('mint_token.py'))
tokens = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tokens)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--users', type=int, default=20000)
parser.add_argument('--output', default='evaluator.tokens.json')
args = parser.parse_args()
if args.users < 10:
    parser.error('--users must be at least 10')
prefix = str(uuid.uuid4())
bundle = {'admin_token': tokens.token('evaluator-admin', True, 7200),
          'user_tokens': [tokens.token(f'{prefix}-{i}', ttl=7200) for i in range(args.users)]}
with os.fdopen(os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as f:
    json.dump(bundle, f)
print(f'Wrote {args.users} user tokens and one admin token to {args.output}; valid for two hours.')
