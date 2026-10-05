# OCC Pricer Cloud (MVP)

The multi-store web version of OCC Pricer, live at **https://cardbox.trading**. One container serves the React client
and the API; PostgreSQL holds the data.

- **Free price check** at `/`: search a card and see its Scryfall market price. No account, as Scryfall's terms require.
- **Store workflow** under `/app` (CardBox sign-in through Auth0, 30-day trial): trade entry with store credit, check or split payouts,
  customers linked by phone number, trade history, tiered buy rates, a store profile, several owners and staff per store,
  multiple locations with every trade tagged to the location it was taken at, inventory per location kept in a storage
  tree each store designs itself (store room, shelf, box, section, or any tiers it likes), and the 19-column receiving POS CSV.
  One CardBox login can belong to several stores and switch between them.
- **Platform admin** at `/app/admin` for the verified owner email: every store and person, plan status, trial end dates,
  renaming stores, and adding, promoting or removing people on any store.

Pricing, condition multipliers, settlement and the POS CSV come from the desktop app's own classes
(`SettlementEngine`, `PricingService`, `TradePosEncoder`, ...), compiled directly from `../src` (see `api/pom.xml`),
so web and desktop produce the same offers and cent allocations.

## Layout

| Path | What |
|---|---|
| `api/` | Spring Boot 3.5 on Java 21, JDBC + Flyway (`api/src/main/resources/db/migration`) |
| `web/` | React + Vite client |
| `Dockerfile` | Builds both into one image; build from the repo root |
| `infra/main.bicep` | Azure resources |
| `deploy.sh` | Deploys to Azure with the Azure CLI |

## Run locally

```sh
docker run -d --name occpg -e POSTGRES_USER=occ -e POSTGRES_PASSWORD=occ -e POSTGRES_DB=occ -p 5432:5432 postgres:17-alpine
cd cloud/api
export APP_SESSION_SECRET=dev-secret-dev-secret-dev-secret-012345 APP_SECURE_COOKIE=false
export AUTH0_DOMAIN=dev-tnnibhkgdbepzjy1.us.auth0.com AUTH0_CLIENT_ID=i8rRy5TlNKCvd4tMFWOqMkJRY1PhCPvq AUTH0_CLIENT_SECRET=<its secret>
mvn -DskipTests package
java -jar target/occ-pricer-cloud.jar import-catalog            # downloads Scryfall bulk data (~500 MB)
java -jar target/occ-pricer-cloud.jar                           # API on :8080
cd ../web && npm install && npm run dev                          # client on :5173, proxies /api to :8080
```

`import-catalog --app.catalog.file=src/test/resources/cards-fixture.json` loads a five-card fixture instead of Scryfall.
`mvn verify` runs the integration tests against PostgreSQL in Docker.

## Azure

Everything lives in one resource group:

| Resource | SKU | Purpose |
|---|---|---|
| Container App `occpricer-app` | Consumption, 0.5 vCPU / 1 GiB, scales to zero | Web client + API |
| Container Apps Job `occpricer-catalog-import` | Consumption, daily 10:30 UTC | Scryfall price import |
| PostgreSQL Flexible Server | Burstable B1ms, 32 GB | Data |
| Container Registry | Basic | Images, built with `az acr build` |
| Key Vault | Standard | Database password and session signing key |
| Log Analytics | Pay as you go, 0.5 GB/day cap | Logs |

Deploy or update (idempotent):

```sh
az login --use-device-code --tenant b5a8b81b-a80c-4aaa-b3cc-2e54736c0fe4
cloud/deploy.sh
```

### Sign-in (Auth0)

Store sign-in uses Auth0 Universal Login in the same Auth0 tenant as cardbox.club, so a person has one CardBox login
for both sites. cardbox.trading has its own Auth0 application, **CardBox Trading** (Regular Web Application), so its
client id (`i8rRy5TlNKCvd4tMFWOqMkJRY1PhCPvq`) and secret can be rotated or switched off without touching cardbox.club. It has the same connections as the CardBox application (Username-Password and Google).

| Setting | Value |
|---|---|
| Allowed Callback URLs | `https://cardbox.trading/api/auth/callback`, `https://www.cardbox.trading/api/auth/callback`, `http://localhost:5173/api/auth/callback`, `http://localhost:8080/api/auth/callback` |
| Allowed Logout URLs | `https://cardbox.trading/`, `https://www.cardbox.trading/`, `http://localhost:5173/`, `http://localhost:8080/` |
| Allowed Web Origins | `https://cardbox.trading`, `https://www.cardbox.trading` |
| Grant types | Authorization Code (with PKCE), no refresh tokens |
| ID token signing | RS256 (Advanced Settings > OAuth). Apps created through the Management API default to HS256, which the app rejects |
| Connections | The same ones the CardBox application uses |

How the app treats a sign-in (`api/.../auth/AuthController.java`):

- Authorization Code + PKCE with scope `openid email profile`; `state` and `nonce` are checked, and the ID token is
  validated against the tenant's keys, issuer and client id.
- Only verified emails are accepted.
- Users are keyed on the Auth0 user id (`users.auth0_sub`), the same id cardbox.club stores. The first sign-in falls
  back to the verified email, which is how accounts made before Auth0 and staff an owner added by email get linked.
- Someone with no account yet is asked to name their store, which starts its trial with them as owner.
- Sign-out clears the app's session and then Auth0's (`/v2/logout`).
- The platform owner is the verified `OWNER_EMAIL` (`toby@vanroojen.com`), reported as `admin` by `/api/auth/me`.
  It is separate from owning a store.

Settings: `AUTH0_DOMAIN` and `AUTH0_CLIENT_ID` are Bicep parameters (`auth0Domain`, `auth0ClientId`); the client secret
is the Key Vault secret `auth0-client-secret`, which `deploy.sh` requires and never generates:

```sh
auth0 apps show <client id> --reveal-secrets --json | jq -r .client_secret \
  | az keyvault secret set --vault-name <vault> -n auth0-client-secret --file /dev/stdin -o none
```

If Universal Login later moves to a shared custom domain such as `login.cardbox.club`, set `auth0Domain` to it here
and on cardbox.club, and signing in on one site signs you in on the other.

### People, stores and roles from CardBox (switched off)

cardbox.club holds the one copy of people, stores and role assignments (`platform_owner`, `store_manager`,
`store_employee`), and both sites read and write it. Trading keeps only its own business data (plan, locations,
inventory, rates, trades) for each CardBox store. This is built but off until CardBox confirms its side is live.
It is the `cardboxEnabled` Bicep parameter (`CARDBOX_ENABLED`, default `false`); `CARDBOX_ENABLED=true cloud/deploy.sh`
turns it on.

With it on:

- Login asks Auth0 for an access token for `https://cardbox.club/api` as well. The server keeps it, encrypted, in
  `cardbox_tokens` (never in the browser) and calls CardBox with it as the signed-in person.
- Each sign-in calls `POST /api/partner/sign-in` and copies the answer onto Trading's rows: `store_manager` becomes
  owner and `store_employee` staff of the Trading store tied to that CardBox store (`tenants.cardbox_store_id`), and
  roles CardBox no longer lists end here. A CardBox store seen for the first time is tied to the person's existing
  Trading store of the same name, so its data carries over, or else gets a new Trading store in trial. A 403 means
  no CardBox account, and someone with no store role can't sign in to the store app.
- The Team and Admin tabs use `/api/cardbox/*`, which forwards only the contract's endpoints (account/roles, people,
  stores, role-grants, role-catalog, role-events) to CardBox. CardBox's `detail` messages are shown as they are.
- Trading's own team changes, store sign-up and store renames are refused (409). Plans and trials stay Trading's.
- The platform owner is `OWNER_EMAIL` or anyone CardBox says is a `platform_owner`.
- Trading never calls the Auth0 Management API or writes `app_metadata`; CardBox does that.

Before switching it on, the Auth0 API `https://cardbox.club/api` must exist and allow the CardBox Trading application,
and each Trading store with data should have a matching store on CardBox with its managers. After switching it on,
the Admin tab lists any Trading store that didn't tie itself by name, to tie by hand.


### CardBox collections in store inventory (switched off)

A store manager or employee can mark a collection on cardbox.club "Sync to store", and its Magic cards show up in
that store's inventory here, kept in step. cardbox.club pushes them server to server (`/api/partner/club-sync/*`,
`clubsync/ClubSyncController.java`) with an Auth0 client-credentials token for the audience
`https://cardbox.trading/api` and scope `inventory:sync`. Every item carries Club's version, so repeats and late
deliveries change nothing, and a full snapshot heals drift. Synced cards are their own inventory lines
(`inventory_items.club_link_id`); staff can't edit them one by one, and an owner picks where each collection's cards
sit and what happens to them when a link ends, from the Inventory page.

It is off until Club's side is ready: `CLUB_SYNC_ENABLED=true cloud/deploy.sh` (Bicep `clubSyncEnabled`). Auth0 is set
up: the "CardBox Trading" API (`https://cardbox.trading/api`, permission `inventory:sync`) is granted to CardBox's
machine-to-machine application, client id `WB4mbh9ky62gjPZHFOHBQCXhYytIie7K`, which is the default allow list (`clubSyncClientIds`,
`CLUB_SYNC_CLIENT_IDS`).
The contract Club builds against is [CLUB_SYNC.md](CLUB_SYNC.md).
### Domain

`cardbox.trading` is registered at Cloudflare and its DNS is hosted there. Both `cardbox.trading` and
`www.cardbox.trading` are bound to the Container App with free Azure-managed certificates, which Azure renews on its
own; `customDomains` in `infra/main.bicep` keeps the bindings on every deploy. The records, all set to
**DNS only** (grey cloud) because Azure cannot issue or renew the certificates through Cloudflare's proxy:

| Type | Name | Value |
|---|---|---|
| A | `@` | the environment's static IP (`az containerapp env show -g occ-pricer -n occpricer-env --query properties.staticIp`) |
| TXT | `asuid` | the app's verification id (`az containerapp show -g occ-pricer -n occpricer-app --query properties.customDomainVerificationId`) |
| CNAME | `www` | the app's default hostname (`az containerapp show -g occ-pricer -n occpricer-app --query properties.configuration.ingress.fqdn`) |
| TXT | `asuid.www` | the same verification id |

A managed certificate can only be issued after its hostname is on the app, so on a brand-new environment (which also
gets a new IP) deploy once with `customDomains=[]`, update the DNS records, then bind each hostname before redeploying
normally:

```sh
az containerapp hostname add -g occ-pricer -n occpricer-app --hostname cardbox.trading
az containerapp env certificate create -g occ-pricer -n occpricer-env --hostname cardbox.trading \
  --certificate-name cardbox-trading --validation-method HTTP
az containerapp hostname bind -g occ-pricer -n occpricer-app --hostname cardbox.trading \
  --environment occpricer-env --certificate cardbox-trading
# Repeat for www.cardbox.trading with --certificate-name www-cardbox-trading --validation-method CNAME.
```

The certificate names must stay `<hostname with dots as dashes>`, which is what the Bicep expects.

Because the app scales to zero, the first request after an idle period waits for the JVM to start (roughly 10 to 20 seconds).

## Not in the MVP yet

Stripe billing (trials are tracked, and an ended trial locks the store workflow),
PostgreSQL row-level security (tenant isolation is enforced in every query and covered by a test),
bounties, trade editing and deletion, receipts as PDF, and importing a store's desktop history.
