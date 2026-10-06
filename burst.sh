#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
burst_python=${PYTHON:-python3}
"$burst_python" -c 'import sys; sys.exit("Python 3.10+ is required; set PYTHON to its executable.") if sys.version_info < (3, 10) else None'
if [ ! -x .burst-env/bin/python ]; then
  "$burst_python" -m venv .burst-env
fi
.burst-env/bin/python -m pip install -q -r scripts/requirements.txt
exec .burst-env/bin/python scripts/burst.py "$@"
