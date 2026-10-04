#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
if [ -e .env ]; then
  echo '.env already exists; keeping it.'
  exit 0
fi
umask 077
python3 - <<'PY'
import secrets
with open('.env', 'x') as f:
    f.write('DB_PASSWORD=' + secrets.token_hex(24) + '\n')
    f.write('JWT_SECRET=' + secrets.token_hex(32) + '\n')
print('Created .env with random local credentials. Do not commit it.')
PY
