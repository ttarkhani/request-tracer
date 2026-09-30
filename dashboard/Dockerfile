FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
# Docker Compose publishes the aggregator on the host's port 8084.
ENV VITE_AGGREGATOR_URL=http://localhost:8084
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
