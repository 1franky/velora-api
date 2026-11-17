-- Huella de los datos de Velora (ADR-027): la misma consulta en producción y en una copia restaurada debe dar el mismo
-- resultado si no hubo importaciones ni borrados después de la instantánea. Solo lectura; no imprime datos personales,
-- solo conteos y resúmenes md5.
--
--   VPS:   docker exec -i velora-postgres psql -U velora -d velora -tA < ops/respaldos/huella.sql > huella-produccion.txt
select 'activities|'            || count(*) from activities
union all select 'activity_files|'        || count(*) from activity_files
union all select 'import_jobs|'           || count(*) from import_jobs
union all select 'activity_route_chunks|' || count(*) from activity_route_chunks
union all select 'exploration_cells|'     || count(*) from exploration_cells
union all select 'flyway_ultima|'         || max(version::int) from flyway_schema_history where success
union all select 'ultimo_import|'         || coalesce(max(created_at)::text, '-') from import_jobs
union all select 'md5_actividades|'       || coalesce(md5(string_agg(id::text, ',' order by id)), '-') from activities
union all select 'md5_archivos|'          || coalesce(md5(string_agg(encode(sha256, 'hex'), ',' order by storage_key)), '-') from activity_files
union all select 'md5_tracks|'            || coalesce(md5(string_agg(md5(st_asbinary(route_geom)), ',' order by activity_id)), '-') from activity_tracks;
