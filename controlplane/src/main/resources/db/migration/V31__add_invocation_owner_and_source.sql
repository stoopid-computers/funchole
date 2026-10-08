-- Activity (a user's recent requests) needs to find invocations by owner and
-- tell real visitor traffic from test runs, without scanning the whole table.
--
-- app_user_id is filled in by a trigger from the flow (or, for a direct
-- function test run, the function), so every code path that inserts an
-- invocation - the gateway, the dashboard, MCP - keeps working unchanged.
-- source is GATEWAY for a request that came in through a public address (its
-- input carries the HTTP request) and TEST for everything else.
ALTER TABLE invocations
    ADD COLUMN app_user_id UUID,
    ADD COLUMN source VARCHAR(20);

UPDATE invocations i
SET app_user_id = f.app_user_id
FROM flows f
WHERE i.app_user_id IS NULL AND i.flow_id = f.id;

UPDATE invocations i
SET app_user_id = fn.app_user_id
FROM functions fn
WHERE i.app_user_id IS NULL AND i.function_id = fn.id;

UPDATE invocations
SET source = CASE
    WHEN kind = 'FLOW' AND input_payload IS NOT NULL
         AND jsonb_typeof(input_payload) = 'object'
         AND jsonb_exists(input_payload, 'hostname')
         AND jsonb_exists(input_payload, 'rawUri') THEN 'GATEWAY'
    ELSE 'TEST'
END;

CREATE FUNCTION invocations_fill_owner_and_source() RETURNS trigger AS $$
BEGIN
    IF NEW.app_user_id IS NULL AND NEW.flow_id IS NOT NULL THEN
        SELECT app_user_id INTO NEW.app_user_id FROM flows WHERE id = NEW.flow_id;
    END IF;
    IF NEW.app_user_id IS NULL AND NEW.function_id IS NOT NULL THEN
        SELECT app_user_id INTO NEW.app_user_id FROM functions WHERE id = NEW.function_id;
    END IF;
    IF NEW.source IS NULL THEN
        NEW.source := CASE
            WHEN NEW.kind = 'FLOW' AND NEW.input_payload IS NOT NULL
                 AND jsonb_typeof(NEW.input_payload) = 'object'
                 AND jsonb_exists(NEW.input_payload, 'hostname')
                 AND jsonb_exists(NEW.input_payload, 'rawUri') THEN 'GATEWAY'
            ELSE 'TEST'
        END;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_invocations_fill_owner_and_source
    BEFORE INSERT ON invocations
    FOR EACH ROW EXECUTE FUNCTION invocations_fill_owner_and_source();

CREATE INDEX idx_invocations_user_created ON invocations (app_user_id, created_at DESC);
CREATE INDEX idx_invocations_flow_created ON invocations (flow_id, created_at DESC);
