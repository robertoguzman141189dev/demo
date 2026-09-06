# Imagen del panel de job-forge.
#
#   docker build -t job-forge-ui:dev apps/job-forge/ui
#
# El panel no es una aplicación con servidor: es un montón de ficheros estáticos
# que el navegador ejecuta. Lo que aquí se despliega es quien los entrega.

# ----------------------------------------------------------------- build -----
FROM node:22-alpine AS build
WORKDIR /build

# El lockfile primero: mientras no cambien las dependencias, Docker reutiliza la
# capa del npm ci, que es la cara del build.
#
# npm ci y no npm install: instala exactamente lo que dice package-lock.json y
# falla si el lockfile no concuerda con package.json. Un build reproducible no
# puede permitirse que una dependencia transitiva cambie de versión sola.
COPY package.json package-lock.json ./
RUN npm ci

COPY angular.json tsconfig.json tsconfig.app.json ./
COPY public public
COPY src src
RUN npm run build

# --------------------------------------------------------------- runtime -----
# nginx-unprivileged y no la imagen oficial de nginx: la oficial arranca su
# proceso maestro como root para poder abrir el puerto 80 y luego degrada los
# workers. Con runAsNonRoot eso no arranca siquiera. Esta variante corre entera
# como uid 101 y escucha en el 8080.
FROM nginxinc/nginx-unprivileged:1.29-alpine AS runtime

# La configuración por defecto sirve desde /usr/share/nginx/html y escribe en
# rutas que readOnlyRootFilesystem bloquea; la nuestra las manda a /tmp.
COPY --chown=101:101 nginx.conf /etc/nginx/nginx.conf

# El builder de Angular deja el bundle del navegador en dist/<proyecto>/browser.
COPY --from=build --chown=101:101 /build/dist/ui/browser /usr/share/nginx/html

USER 101:101
EXPOSE 8080

# Sin daemon: el proceso tiene que ser PID 1 para recibir el SIGTERM que manda
# Kubernetes. Un nginx demonizado deja al contenedor sin nadie a quien avisar.
CMD ["nginx", "-g", "daemon off;"]
