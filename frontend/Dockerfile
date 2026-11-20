# 프런트 운영 이미지: next build 후 server.mjs (전달 헤더 정리) 로 시작.
# /api 는 Route Handler 가 실행 시점의 BACKEND_URL·PROXY_SHARED_SECRET 으로 넘긴다.
FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
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
