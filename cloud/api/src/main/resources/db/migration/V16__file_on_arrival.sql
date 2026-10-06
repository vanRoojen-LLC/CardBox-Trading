-- Stores that trust their storage rules can have arriving cards (from trades and CardBox collections) filed by them
-- straight away. Off by default: rules suggest and staff confirm.
ALTER TABLE tenants ADD COLUMN file_on_arrival boolean NOT NULL DEFAULT false;
