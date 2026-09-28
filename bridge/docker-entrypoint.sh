#!/bin/sh
# Docker creates a bind-mounted ./bridge-data owned by root on first start, and the bridge
# runs as the unprivileged "node" user. Hand /data over, then drop root for good.
set -e
if [ "$(id -u)" = "0" ]; then
  chown -R node:node /data
  exec setpriv --reuid=node --regid=node --init-groups "$@"
fi
exec "$@"
