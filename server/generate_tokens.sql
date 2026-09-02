-- 1. Table: admin_tokens
CREATE TABLE IF NOT EXISTS admin_tokens (
    id SERIAL PRIMARY KEY,
    teacher_code VARCHAR(50) NOT NULL,
    username VARCHAR(100),
    token_plain VARCHAR(20) NOT NULL,
    token_hash TEXT NOT NULL,
    token_valid_from TIMESTAMP DEFAULT NOW(),
    token_valid_until TIMESTAMP DEFAULT (NOW() + INTERVAL '7 days'),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_admin_tokens_teacher ON admin_tokens(teacher_code, is_active);

-- 2. Stored Procedure: generate_7day_tokens
CREATE OR REPLACE PROCEDURE generate_7day_tokens()
LANGUAGE plpgsql
AS $$
DECLARE
    rec RECORD;
    v_token TEXT;
    v_expiry TIMESTAMP;
BEGIN
    v_expiry := NOW() + INTERVAL '7 days';

    FOR rec IN 
        SELECT teacher_code, username 
        FROM mst_teacher 
        WHERE (status IS NULL OR status = true)
          AND (token_valid_until IS NULL OR token_valid_until <= NOW())
    LOOP
        v_token := 'AT-' || 
                   UPPER(SUBSTRING(MD5(RANDOM()::TEXT || CLOCK_TIMESTAMP()::TEXT) FROM 1 FOR 4)) || 
                   '-' || 
                   UPPER(SUBSTRING(MD5(RANDOM()::TEXT || CLOCK_TIMESTAMP()::TEXT) FROM 5 FOR 4));

        UPDATE mst_teacher
        SET 
            login_token_hash = MD5(v_token),
            token_valid_from = NOW(),
            token_valid_until = v_expiry
        WHERE teacher_code = rec.teacher_code;

        UPDATE admin_tokens 
        SET is_active = FALSE 
        WHERE teacher_code = rec.teacher_code AND is_active = TRUE;

        INSERT INTO admin_tokens (teacher_code, username, token_plain, token_hash, token_valid_from, token_valid_until)
        VALUES (rec.teacher_code, rec.username, v_token, MD5(v_token), NOW(), v_expiry);
    END LOOP;
END;
$$;
