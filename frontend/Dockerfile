FROM node:22-alpine AS build

WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend ./
RUN npm run build

FROM nginx:1.28-alpine

RUN apk add --no-cache openssl
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY deploy/nginx/entrypoint.sh /usr/local/bin/knowbase-nginx-entrypoint
COPY --from=build /workspace/frontend/dist /usr/share/nginx/html
RUN chmod +x /usr/local/bin/knowbase-nginx-entrypoint \
    && mkdir -p /etc/nginx/tls /var/cache/nginx

EXPOSE 80 443
ENTRYPOINT ["/usr/local/bin/knowbase-nginx-entrypoint"]
