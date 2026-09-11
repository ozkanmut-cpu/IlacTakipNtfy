FROM node:22-alpine
WORKDIR /app
COPY package.json ./
COPY src ./src
RUN node --check src/server.mjs && node --check src/apns.mjs
USER node
CMD ["node", "src/server.mjs"]
