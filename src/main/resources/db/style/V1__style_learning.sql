CREATE TABLE learning_checkpoint (
    conversation_id text PRIMARY KEY, cursor bigint NOT NULL DEFAULT 0,
    scanned bigint NOT NULL DEFAULT 0, effective bigint NOT NULL DEFAULT 0,
    pending_since timestamptz, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE interaction_fragment (
    id bigserial PRIMARY KEY, conversation_id text NOT NULL, fingerprint text NOT NULL,
    extractor_version text NOT NULL, through_sequence bigint NOT NULL, payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(conversation_id, extractor_version, fingerprint)
);
CREATE INDEX fragment_conversation ON interaction_fragment(conversation_id,id);
CREATE TABLE extraction_job (
    id uuid PRIMARY KEY, conversation_id text NOT NULL, kind text NOT NULL CHECK(kind IN ('extract','embed')),
    status text NOT NULL CHECK(status IN ('pending','running','retry','done','failed')),
    from_sequence bigint NOT NULL, through_sequence bigint NOT NULL,
    extractor_version text NOT NULL, fragment_id bigint REFERENCES interaction_fragment(id),
    profile text NOT NULL DEFAULT '', payload jsonb NOT NULL,
    attempts integer NOT NULL DEFAULT 0, claim_token uuid, lease_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT now(), error_code text,
    scanned integer NOT NULL DEFAULT 0, effective integer NOT NULL DEFAULT 0,
    rejected integer NOT NULL DEFAULT 0, input_tokens integer, output_tokens integer,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX one_extraction_window ON extraction_job(conversation_id,from_sequence,extractor_version) WHERE kind='extract';
CREATE UNIQUE INDEX one_embedding_job ON extraction_job(fragment_id,profile) WHERE kind='embed';
CREATE INDEX runnable_jobs ON extraction_job(status,next_attempt_at);
CREATE TABLE fragment_embedding (
    fragment_id bigint NOT NULL REFERENCES interaction_fragment(id), profile text NOT NULL, request_profile text NOT NULL,
    dimensions integer NOT NULL CHECK(dimensions > 0), embedding public.vector NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(fragment_id,profile),
    CHECK(public.vector_dims(embedding)=dimensions)
);
