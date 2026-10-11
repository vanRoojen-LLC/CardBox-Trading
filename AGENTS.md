# CardBox Trading agent instructions

The shared working rules for every agent harness (Claude and Codex) live in one place: the section
"Working rules for every agent harness" of the CardBox repository's `AGENTS.md`
(https://github.com/vanRoojen-LLC/CardBox/blob/main/AGENTS.md). Read it before working here. Do not
copy those rules into this file; a second copy drifts.

Only what is specific to this repository belongs below. Values that change (the next Flyway
migration, image tags) are read from the repository, never written here.

- Default branch is `master`. Every pull request targets it; never stack.
- cardbox.trading runs on CardBox's Rackspace Spot Cloudspace; its manifests and runbook are CardBox
  `deploy/spot-trading/`. Every green push to `master` publishes the image to GHCR through the `publish`
  job in `.github/workflows/cloud.yml`, and the cluster moves to it within five minutes. Never deploy by
  hand. Production keeps `CARDBOX_ENABLED` and `CLUB_SYNC_ENABLED` true; the app defaults both to false.
- Flyway migrations are in `cloud/api/src/main/resources/db/migration`. Claim the next version in the
  PR title after checking `master` and the open PRs; two PRs with the same version break `master`.
- This repository is public: never open issues here. Trading problems are filed in CardBox with the
  `product:trading` label.
- API tests start their own PostgreSQL with Testcontainers: run them with a Docker daemon and
  `env -u CARDBOX_ENABLED`. The Swing desktop app at the repository root needs JDK 25 and is built
  only by its own CI.
- The Club integration contract is `cloud/CLUB_SYNC.md`; Club is the system of record for people,
  stores, roles and cards.
