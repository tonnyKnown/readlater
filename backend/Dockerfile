FROM node:22-alpine
WORKDIR /app
COPY backend/package.json ./
RUN npm install --omit=dev --no-audit --no-fund
COPY backend/src ./src
COPY frontend/dist ./public
ENV PORT=3000 DB_PATH=/app/data/data.db DIST_PATH=/app/public
VOLUME /app/data
CMD ["node", "src/server.js"]
