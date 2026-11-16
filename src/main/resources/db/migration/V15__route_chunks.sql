-- Caminos nuevos (ADR-019, ADR-031): el recorrido de cada actividad partido en trozos de hasta 32 vértices, con índice
-- GiST. Con el recorrido completo, el recuadro de casi cualquier rodada toca el de las demás (todas salen de la misma
-- zona) y cada muestra se comparaba contra recorridos enteros: 10 s con 150 rodadas. Con trozos, el índice deja solo
-- los que pasan cerca de cada muestra. Es el mismo trazo: las distancias, y por lo tanto el resultado, no cambian.
CREATE TABLE activity_route_chunks (
    activity_id uuid NOT NULL REFERENCES activity_tracks (activity_id) ON DELETE CASCADE,
    geom        geometry(Geometry, 4326) NOT NULL
);

CREATE INDEX activity_route_chunks_geom_gist ON activity_route_chunks USING gist (geom);
CREATE INDEX activity_route_chunks_activity ON activity_route_chunks (activity_id);

-- Un recorrido solo se inserta o se borra (el reproceso borra y vuelve a insertar): el trigger crea los trozos en la
-- misma transacción y la llave foránea los borra.
CREATE FUNCTION velora_chunk_route() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO activity_route_chunks (activity_id, geom)
    SELECT NEW.activity_id, ST_Subdivide(NEW.route_geom, 32);
    RETURN NULL;
END
$$;

CREATE TRIGGER activity_tracks_chunks
    AFTER INSERT ON activity_tracks
    FOR EACH ROW EXECUTE FUNCTION velora_chunk_route();

INSERT INTO activity_route_chunks (activity_id, geom)
SELECT activity_id, ST_Subdivide(route_geom, 32) FROM activity_tracks;
