# The Centsible Home Assistant app: Actual's server and the bridge in one image, on the
# versions the bridge is tested against. Build from the repository root:
#   docker build -f homeassistant/centsible/Dockerfile --build-arg ACTUAL_VERSION=26.11.0 .
FROM node:22-bookworm-slim AS build
# better-sqlite3 (both servers use it) compiles from source when no prebuilt binary matches.
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ && rm -rf /var/lib/apt/lists/*
ARG ACTUAL_VERSION
WORKDIR /app/bridge
COPY bridge/package.json bridge/package-lock.json ./
RUN npm ci
COPY bridge/tsconfig.json bridge/tsconfig.build.json ./
COPY bridge/src ./src
RUN npm run build && npm prune --omit=dev
# Actual's server (with its web app) from npm, at the release the bridge is paired with.
WORKDIR /app/actual
RUN test -n "$ACTUAL_VERSION" && echo '{"private":true}' > package.json \
 && npm install --omit=dev --no-audit --no-fund "@actual-app/sync-server@${ACTUAL_VERSION}"

FROM node:22-bookworm-slim
ARG BRIDGE_RELEASE=
ARG ACTUAL_VERSION
ENV BRIDGE_RELEASE=${BRIDGE_RELEASE} \
    ACTUAL_VERSION=${ACTUAL_VERSION} \
    NODE_ENV=production
LABEL io.hass.type="addon" \
      io.hass.name="Centsible" \
      io.hass.version="${BRIDGE_RELEASE}" \
      org.opencontainers.image.source="https://github.com/Flexingg/Centsible" \
      org.opencontainers.image.description="Centsible for Home Assistant: Actual Budget's server and the Centsible bridge" \
      org.opencontainers.image.licenses="GPL-3.0"
WORKDIR /app
COPY --from=build /app/bridge/package.json bridge/package.json
COPY --from=build /app/bridge/node_modules bridge/node_modules
COPY --from=build /app/bridge/dist bridge/dist
COPY --from=build /app/actual/node_modules actual/node_modules
COPY homeassistant/centsible/run.mjs run.mjs
EXPOSE 8787 5006
CMD ["node", "/app/run.mjs"]
