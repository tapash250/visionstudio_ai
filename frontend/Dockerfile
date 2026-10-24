FROM node:20-alpine AS builder

# Prisma detects the OpenSSL version to pick its query engine; Alpine ships none by default.
RUN apk add --no-cache openssl

WORKDIR /app
COPY package*.json ./
RUN npm ci

COPY . .
# The schema only arrives with the sources, after install, so the Prisma client is generated here.
RUN npx prisma generate
RUN npm run build

FROM node:20-alpine AS runner
RUN apk add --no-cache openssl
WORKDIR /app

ENV NODE_ENV production
ENV NEXT_TELEMETRY_DISABLED 1

COPY --from=builder /app/public ./public
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static

EXPOSE 3000
ENV PORT 3000
ENV HOSTNAME "0.0.0.0"

CMD ["node", "server.js"]
