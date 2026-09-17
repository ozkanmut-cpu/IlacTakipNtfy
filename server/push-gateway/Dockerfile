FROM binwiederhier/ntfy:v2.28.0 AS ntfy-cli

FROM node:22-alpine AS gateway-deps
WORKDIR /app
RUN apk add --no-cache python3 make g++
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund

FROM node:22-alpine
WORKDIR /app
RUN apk add --no-cache libstdc++
COPY --from=ntfy-cli /usr/bin/ntfy /usr/bin/ntfy
COPY --from=gateway-deps /app/node_modules ./node_modules
COPY package.json package-lock.json ./
RUN node -e "import('better-sqlite3').then(({default:Database})=>{const db=new Database(':memory:'); db.exec('CREATE TABLE smoke (id INTEGER)'); db.close();})"
COPY src ./src
RUN node --check src/server.mjs \
    && node --check src/apns.mjs \
    && node --check src/fcm.mjs \
    && node --check src/install-auth.mjs \
    && node --check src/relay-metadata-store.mjs \
    && node --check src/relay-queue-store.mjs \
    && test "$(ntfy --version)" = "ntfy version 2.28.0"
USER node
CMD ["node", "src/server.mjs"]
