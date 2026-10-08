#!/bin/sh
# Stops the TMS API started with start.sh.
cd "$(dirname "$0")" || exit 1
if [ ! -f tms-api.pid ] || ! kill -0 "$(cat tms-api.pid)" 2>/dev/null; then
  echo "Not running."
  rm -f tms-api.pid
  exit 0
fi
pid=$(cat tms-api.pid)
kill "$pid"
for _ in $(seq 1 30); do
  kill -0 "$pid" 2>/dev/null || { rm -f tms-api.pid; echo "Stopped."; exit 0; }
  sleep 1
done
echo "Still running after 30 s; forcing it to stop."
kill -9 "$pid"
rm -f tms-api.pid
