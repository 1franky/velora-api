-- Mapa de calor (ADR-017). Factor de privacidad de una actividad, igual al de TrackPrivacy.forActivity (ADR-016):
-- un número estable en [0.7, 1.3) a partir de los 53 bits altos de la mitad alta (inicio) o baja (fin) del UUID.
-- Así el mapa de calor oculta exactamente lo mismo que el detalle de cada actividad.
CREATE FUNCTION velora_privacy_factor(id uuid, at_start boolean)
    RETURNS double precision
    LANGUAGE sql
    IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT 0.7 + 0.6 * ((('x' || substr(replace(id::text, '-', ''), CASE WHEN at_start THEN 1 ELSE 17 END, 16))::bit(64)
        >> 11)::bigint::double precision / 9007199254740992.0)
$$;
