#!/usr/bin/env bash
set -eu
listen_port=${PORT:-8080}
if [[ ! "$listen_port" =~ ^[0-9]+$ ]] || (( listen_port < 1024 || listen_port > 65535 || listen_port == 8081 )); then
    echo 'PORT must be a non-privileged TCP port other than the internal port 8081' >&2
    exit 1
fi
sed "s/__PORT__/$listen_port/" /app/haproxy.cfg > /tmp/seat-haproxy.cfg
haproxy -c -f /tmp/seat-haproxy.cfg
java -XX:MaxRAMPercentage=70 -jar /app/app.jar --server.port=8081 --server.address=127.0.0.1 &
spring_pid=$!
haproxy -db -f /tmp/seat-haproxy.cfg &
proxy_pid=$!
shutdown() {
    # Stop admitting traffic, then let Spring finish its in-flight transactions.
    kill -USR1 "$proxy_pid" 2>/dev/null || true
    kill -TERM "$spring_pid" 2>/dev/null || true
    wait "$spring_pid" 2>/dev/null || true
    kill -TERM "$proxy_pid" 2>/dev/null || true
    wait "$proxy_pid" 2>/dev/null || true
}
trap 'shutdown; exit 0' TERM INT
set +e
wait -n "$spring_pid" "$proxy_pid"
exit_status=$?
shutdown
exit "$exit_status"
