# syntax=docker/dockerfile:1.7

FROM node:22-bookworm-slim AS dependencies

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*

RUN corepack enable \
    && corepack prepare pnpm@10.33.3 --activate

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
COPY patches ./patches
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
