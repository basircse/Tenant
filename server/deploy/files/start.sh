#!/bin/sh
# Starts the TMS API (and the web console it serves) in the background.
# Settings: config/application.properties. Log: logs/tms-api.log.
cd "$(dirname "$0")" || exit 1
if [ -f tms-api.pid ] && kill -0 "$(cat tms-api.pid)" 2>/dev/null; then
  echo "Already running (pid $(cat tms-api.pid))."
  exit 0
fi
mkdir -p logs
nohup java ${JAVA_OPTS:--Xms256m -Xmx768m} -jar tms-api.jar > logs/console.out 2>&1 &
echo $! > tms-api.pid
echo "Started (pid $!). Follow the log with: tail -f logs/tms-api.log"
