-- Privacidad de inicio y fin del recorrido (ADR-016). 0 = desactivada (por defecto).
ALTER TABLE users
    ADD COLUMN track_privacy_meters integer NOT NULL DEFAULT 0
        CHECK (track_privacy_meters IN (0, 200, 300, 500, 1000));

-- Recorta `start_m` metros al inicio y `end_m` al final de un recorrido MultiLineString, midiendo sobre el propio
-- camino con distancias geodésicas y recorriendo los tramos en orden (las pausas no cuentan como distancia).
-- Devuelve NULL si no queda nada. No modifica el recorrido guardado: solo se usa al servirlo.
CREATE FUNCTION velora_trim_route(route geometry, start_m double precision, end_m double precision)
    RETURNS geometry
    LANGUAGE plpgsql
    IMMUTABLE STRICT PARALLEL SAFE
AS $$
DECLARE
    part      geography;
    part_len  double precision;
    total     double precision := 0;
    acc       double precision := 0;
    keep_from double precision;
    keep_to   double precision;
    a         double precision;
    b         double precision;
    kept      geometry[] := '{}';
BEGIN
    SELECT coalesce(sum(ST_Length((d).geom::geography)), 0) INTO total FROM (SELECT ST_Dump(route) AS d) s;
    keep_from := greatest(start_m, 0);
    keep_to := total - greatest(end_m, 0);
    IF keep_to <= keep_from THEN
        RETURN NULL;
    END IF;
    FOR part IN SELECT (d).geom::geography FROM (SELECT ST_Dump(route) AS d) s ORDER BY (d).path LOOP
        part_len := ST_Length(part);
        IF part_len > 0 AND acc + part_len > keep_from AND acc < keep_to THEN
            a := greatest(0, (keep_from - acc) / part_len);
            b := least(1, (keep_to - acc) / part_len);
            IF b > a THEN
                kept := kept || ST_LineSubstring(part, a, b)::geometry;
            END IF;
        END IF;
        acc := acc + part_len;
    END LOOP;
    IF cardinality(kept) = 0 THEN
        RETURN NULL;
    END IF;
    RETURN ST_SetSRID(ST_Multi(ST_Collect(kept)), 4326);
END;
$$;
