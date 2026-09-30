# syntax=docker/dockerfile:1.7

# Build from the repository root, which is the context (the image needs the
# workspace lockfile and packages/core):
#   docker build -f apps/api/Dockerfile -t halo-api .
# Dockerfile.dockerignore beside this file limits that context to what the API
# needs; BuildKit reads it in place of a root .dockerignore.

FROM node:22-bookworm-slim AS dependencies

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*

RUN corepack enable \
    && corepack prepare pnpm@10.33.3 --activate

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/mobile/patches ./apps/mobile/patches
COPY apps/api/package.json ./apps/api/package.json
COPY packages/core/package.json ./packages/core/package.json

RUN pnpm install --frozen-lockfile --prod --filter @halo/api...

COPY apps/api ./apps/api
COPY packages/core ./packages/core

FROM node:22-bookworm-slim AS runtime

ENV NODE_ENV=production
WORKDIR /app

COPY --from=dependencies --chown=node:node /app /app

RUN mkdir -p /data \
    && chown node:node /data

USER node

EXPOSE 8787

CMD ["node", "--import", "tsx", "apps/api/src/index.ts"]
