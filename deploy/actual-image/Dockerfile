# The Actual server release the bridge is built and tested against, republished as
# ghcr.io/flexingg/centsible-actual so ":latest" always pairs with the bridge's ":latest".
# That lets one "Update" (Watchtower) move both together without ever running an
# Actual version the bridge doesn't know. CI builds this; nothing else changes.
ARG ACTUAL_VERSION
FROM actualbudget/actual-server:${ACTUAL_VERSION}
LABEL org.opencontainers.image.source=https://github.com/Flexingg/Centsible \
      org.opencontainers.image.description="Actual Budget server, the release the Centsible bridge is paired with" \
      org.opencontainers.image.licenses=MIT
