-- Concourse schema v1. Phone numbers are stored hashed; contact holds the sendable number (encrypt at rest in prod).
CREATE TABLE contact (
  phone_hash      VARCHAR(64) PRIMARY KEY,
  e164            VARCHAR(32) NOT NULL,
  role            VARCHAR(16) NOT NULL,            -- PARENT | CHILD
  language        VARCHAR(8)  NOT NULL DEFAULT 'te',
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE journey (
  id                 UUID PRIMARY KEY,
  parent_phone_hash  VARCHAR(64),
  child_phone_hash   VARCHAR(64) NOT NULL,
  parent_language    VARCHAR(8)  NOT NULL DEFAULT 'te',
  status             VARCHAR(32) NOT NULL DEFAULT 'PRE_TRIP',
  consent_at         TIMESTAMP WITH TIME ZONE,
  expires_at         TIMESTAMP WITH TIME ZONE,
  created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX journey_parent_idx ON journey(parent_phone_hash);
CREATE INDEX journey_child_idx  ON journey(child_phone_hash);

CREATE TABLE flight_segment (
  id                  UUID PRIMARY KEY,
  journey_id          UUID NOT NULL REFERENCES journey(id) ON DELETE CASCADE,
  seq                 INT  NOT NULL,
  airline             VARCHAR(64),
  flight_number       VARCHAR(16) NOT NULL,
  origin              VARCHAR(3)  NOT NULL,
  destination         VARCHAR(3)  NOT NULL,
  scheduled_departure TIMESTAMP WITH TIME ZONE NOT NULL,
  scheduled_arrival   TIMESTAMP WITH TIME ZONE NOT NULL,
  terminal            VARCHAR(16),
  gate                VARCHAR(16),
  source              VARCHAR(32) NOT NULL DEFAULT 'ticket',
  last_verified_at    TIMESTAMP WITH TIME ZONE
);

CREATE TABLE stage (
  id                  UUID PRIMARY KEY,
  journey_id          UUID NOT NULL REFERENCES journey(id) ON DELETE CASCADE,
  type                VARCHAR(32) NOT NULL,
  airport             VARCHAR(3),
  scheduled_at        TIMESTAMP WITH TIME ZONE,
  status              VARCHAR(16) NOT NULL DEFAULT 'PLANNED', -- PLANNED | SENT | ACKED | SKIPPED
  instruction_version INT NOT NULL DEFAULT 1,
  sent_at             TIMESTAMP WITH TIME ZONE,
  acknowledged_at     TIMESTAMP WITH TIME ZONE
);
CREATE INDEX stage_journey_idx ON stage(journey_id, scheduled_at);

CREATE TABLE evidence (
  id               UUID PRIMARY KEY,
  journey_id       UUID NOT NULL REFERENCES journey(id) ON DELETE CASCADE,
  stage_id         UUID REFERENCES stage(id) ON DELETE SET NULL,
  type             VARCHAR(32) NOT NULL,  -- ticket | sign | flight_status | parent_ack
  extracted_facts  TEXT NOT NULL,         -- JSON, sanitized
  confidence       DOUBLE PRECISION,
  received_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  expires_at       TIMESTAMP WITH TIME ZONE
);

CREATE TABLE event (
  id                UUID PRIMARY KEY,
  journey_id        UUID REFERENCES journey(id) ON DELETE CASCADE,
  actor             VARCHAR(16) NOT NULL,  -- parent | child | system | ops
  type              VARCHAR(48) NOT NULL,
  sanitized_payload TEXT,
  ts                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX event_journey_idx ON event(journey_id, ts);

CREATE TABLE outbound_job (
  id          UUID PRIMARY KEY,
  journey_id  UUID NOT NULL REFERENCES journey(id) ON DELETE CASCADE,
  run_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  kind        VARCHAR(32) NOT NULL,
  payload     TEXT NOT NULL,              -- JSON
  status      VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING | SENT | FAILED
  attempts    INT NOT NULL DEFAULT 0,
  last_error  TEXT
);
CREATE INDEX outbound_job_due_idx ON outbound_job(status, run_at);
