# PostgreSQL 18 + PostGIS 3.7 para el despliegue (compose.deploy.yaml).
#
# Por qué no `postgis/postgis:18-3.7` (la imagen de compose.yaml y de Testcontainers): solo se publica para
# amd64 y el VPS es arm64 (aarch64). Se parte de la imagen oficial `postgres:18` y se instala PostGIS desde el
# repositorio oficial PGDG que ya trae configurado. La extensión la crea la migración V1__enable_postgis.sql.
FROM postgres:18

ARG POSTGIS_VERSION=3.7.0+dfsg-1.pgdg13+1

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        "postgresql-18-postgis-3=${POSTGIS_VERSION}" \
        "postgresql-18-postgis-3-scripts=${POSTGIS_VERSION}" \
    && rm -rf /var/lib/apt/lists/*
