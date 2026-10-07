-- Who sees preview games is one list shared with CardBox Club (owner, 2026-10-07): platform owners, plus everyone
-- CardBox gave the preview_access role. Copied from CardBox at each sign-in and whenever the Admin tab reads roles,
-- like platform_owner beside it.
ALTER TABLE cardbox_tokens ADD COLUMN preview BOOLEAN NOT NULL DEFAULT false;
