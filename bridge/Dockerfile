# Keep the @actual-app/api version in package.json on the same release line (YY.M)
# as your actual-server image. Renovate bumps both together.
FROM node:22-bookworm-slim AS build
# better-sqlite3 compiles from source when no prebuilt binary matches (new Node
# releases, arm64). Build tools stay in this stage; the final image only gets the result.
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-bookworm-slim
# CI passes the release (0.2.<run>) so the bridge can tell when a newer one is out.
ARG BRIDGE_RELEASE=
ENV BRIDGE_RELEASE=${BRIDGE_RELEASE}
ENV NODE_ENV=production \
    BRIDGE_DATA_DIR=/data \
    BRIDGE_PORT=8787
WORKDIR /app
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY docker-entrypoint.sh /usr/local/bin/
VOLUME ["/data"]
EXPOSE 8787
# Starts as root only to fix /data ownership, then runs as "node" (see the script).
ENTRYPOINT ["docker-entrypoint.sh"]
HEALTHCHECK --interval=30s --timeout=5s CMD node -e "fetch('http://127.0.0.1:8787/v1/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "dist/main.js"]
