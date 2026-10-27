-- Day 1: ticket → itinerary. Arrival may be absent on some tickets (flagged unverified, child confirms).
ALTER TABLE journey ADD COLUMN passenger_first_name VARCHAR(64);
ALTER TABLE journey ADD COLUMN itinerary_confirmed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE flight_segment ALTER COLUMN scheduled_arrival DROP NOT NULL;
ALTER TABLE flight_segment ADD COLUMN unverified_fields TEXT;
