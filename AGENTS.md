# CardBox Trading agent instructions

The shared working rules for every agent harness (Claude and Codex) live in one place: the section
"Working rules for every agent harness" of the CardBox repository's `AGENTS.md`
(https://github.com/vanRoojen-LLC/CardBox/blob/main/AGENTS.md). Read it before working here. Do not
copy those rules into this file; a second copy drifts.

Only what is specific to this repository belongs below. Values that change (the next Flyway
migration, image tags) are read from the repository, never written here.

- Default branch is `master`. Every pull request targets it; never stack.
- Every green push to `master` deploys cardbox.trading through the `deploy` job in
  `.github/workflows/cloud.yml`. Never deploy by hand. Never run `cloud/deploy.sh` without
  `CARDBOX_ENABLED=true CLUB_SYNC_ENABLED=true`; it defaults both to false and silently turns off the
  CardBox link and the Club inventory sync.
- Flyway migrations are in `cloud/api/src/main/resources/db/migration`. Claim the next version in the
  PR title after checking `master` and the open PRs; two PRs with the same version break `master`.
- This repository is public: never open issues here. Trading problems are filed in CardBox with the
  `product:trading` label.
- API tests start their own PostgreSQL with Testcontainers: run them with a Docker daemon and
  `env -u CARDBOX_ENABLED`. The Swing desktop app at the repository root needs JDK 25 and is built
  only by its own CI.
- The Club integration contract is `cloud/CLUB_SYNC.md`; Club is the system of record for people,
  stores, roles and cards.
