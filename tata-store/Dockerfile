FROM node:22-alpine
ENV NODE_ENV=production HOST=0.0.0.0 PORT=8840 TATA_STORE_STORAGE=/data
WORKDIR /app
COPY --chown=node:node package.json server.js seed-apps.json ./
COPY --chown=node:node public ./public
RUN mkdir -p /data && chown node:node /data
VOLUME ["/data"]
USER node
EXPOSE 8840
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s CMD node -e "fetch('http://127.0.0.1:8840/api/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "server.js"]
