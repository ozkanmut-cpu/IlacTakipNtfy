FROM binwiederhier/ntfy:v2.28.0 AS ntfy-cli

FROM node:22-alpine
WORKDIR /app
COPY --from=ntfy-cli /usr/bin/ntfy /usr/bin/ntfy
COPY package.json ./
COPY src ./src
RUN node --check src/server.mjs \
    && node --check src/apns.mjs \
    && test "$(ntfy --version)" = "ntfy version 2.28.0"
USER node
CMD ["node", "src/server.mjs"]
