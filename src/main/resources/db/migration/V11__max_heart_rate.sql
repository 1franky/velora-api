-- FC máxima opcional del usuario para las zonas cardiacas (ADR-020). Sin ella no se calculan zonas.
ALTER TABLE users
    ADD COLUMN max_heart_rate_bpm smallint CHECK (max_heart_rate_bpm BETWEEN 100 AND 230);
