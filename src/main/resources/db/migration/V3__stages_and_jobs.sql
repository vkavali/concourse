-- Day 3: the journey engine. Stages are planned from flight times; outbound_job rows drive the scheduler.
ALTER TABLE journey ADD COLUMN intro_sent_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE journey ADD COLUMN last_parent_message_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE journey ADD COLUMN current_stage_id UUID;
ALTER TABLE stage ADD COLUMN seq INT NOT NULL DEFAULT 0;
ALTER TABLE stage ADD COLUMN segment_seq INT;
CREATE INDEX stage_journey_seq_idx ON stage(journey_id, seq);
ALTER TABLE outbound_job ADD COLUMN stage_id UUID REFERENCES stage(id) ON DELETE CASCADE;
