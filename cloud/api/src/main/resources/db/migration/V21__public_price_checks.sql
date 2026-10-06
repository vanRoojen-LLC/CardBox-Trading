-- How much the free price check is used: one row per UTC day, bumped on every successful search the API answers.
-- Answers may be cached by browsers and any CDN for an hour, so searches served from a cache are not counted.
CREATE TABLE public_price_checks (
    day    date PRIMARY KEY,
    checks bigint NOT NULL DEFAULT 0
);
