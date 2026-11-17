# 프런트 운영 이미지: next build 후 server.mjs (전달 헤더 정리) 로 시작.
# /api rewrites 는 빌드 시점의 BACKEND_URL(또는 BACKEND_HOSTPORT)로 고정되므로 빌드 인자로도 넘긴다.
FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG BACKEND_URL
ARG BACKEND_HOSTPORT
ARG NEXT_PUBLIC_SESSION_POLL_MS
RUN npm run build

FROM node:22-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/package.json /app/package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY --from=build /app/.next .next
COPY --from=build /app/public public
COPY --from=build /app/server.mjs /app/next.config.ts ./
USER node
EXPOSE 3000
CMD ["node", "server.mjs"]
