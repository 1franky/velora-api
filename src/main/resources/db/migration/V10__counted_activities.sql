-- Actividades que cuentan para la analítica (ADR-020): de cada grupo de posibles duplicados sin resolver solo
-- cuenta una. Misma regla que DuplicateHeuristic (dedup-heuristic/1; mantener sincronizadas): inicio a ±2 min,
-- duración a ±5 %, distancia a ±3 % y recuadros que se tocan (las dos últimas solo si ambas tienen el dato).
-- Gana la de más datos: más series, luego más puntos, luego la importada primero y, por último, el id.
-- Sin CTE: la vista se integra en cada consulta y la búsqueda de la pareja usa el índice (user_id, started_at).
CREATE VIEW counted_activities AS
SELECT a.id, a.user_id, a.bike_id, a.device_id, a.title, a.sport_type, a.sub_type, a.started_at, a.local_start_date,
       a.elapsed_time_seconds, a.moving_time_seconds, a.distance_meters, a.elevation_gain_meters,
       a.elevation_loss_meters, a.average_speed_mps, a.max_speed_mps, a.average_heart_rate_bpm, a.max_heart_rate_bpm,
       a.average_power_watts, a.max_power_watts, a.metrics_provenance
FROM activities a
LEFT JOIN activity_tracks ta ON ta.activity_id = a.id
WHERE NOT EXISTS (
    SELECT 1
    FROM activities b
    LEFT JOIN activity_tracks tb ON tb.activity_id = b.id
    WHERE b.user_id = a.user_id AND b.id <> a.id
      AND b.started_at BETWEEN a.started_at - interval '2 minutes' AND a.started_at + interval '2 minutes'
      AND abs(b.elapsed_time_seconds - a.elapsed_time_seconds)
          <= 0.05 * greatest(b.elapsed_time_seconds, a.elapsed_time_seconds)
      AND (a.distance_meters IS NULL OR b.distance_meters IS NULL
           OR abs(b.distance_meters - a.distance_meters) <= 0.03 * greatest(b.distance_meters, a.distance_meters))
      AND (ta.bbox IS NULL OR tb.bbox IS NULL OR ta.bbox && tb.bbox)
      AND ((SELECT count(*) FROM activity_streams s WHERE s.activity_id = b.id), coalesce(tb.point_count, 0),
           a.created_at, a.id)
          > ((SELECT count(*) FROM activity_streams s WHERE s.activity_id = a.id), coalesce(ta.point_count, 0),
             b.created_at, b.id));
