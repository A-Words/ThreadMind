FROM node:24-bookworm-slim AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
COPY server/package.json server/package.json
RUN npm ci
COPY server server
RUN npm run build -w @threadmind/server

FROM node:24-bookworm-slim AS runtime
ENV NODE_ENV=production
WORKDIR /workspace
COPY --from=build /workspace/package.json /workspace/package-lock.json ./
COPY --from=build /workspace/server/package.json server/package.json
COPY --from=build /workspace/server/dist server/dist
COPY --from=build /workspace/node_modules node_modules
USER node

FROM runtime AS worker
CMD ["node", "server/dist/src/worker/main.js"]

FROM runtime AS api
EXPOSE 3000
CMD ["node", "server/dist/src/api/main.js"]
