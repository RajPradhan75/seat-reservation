# Manual interview demo

Use this guide alongside `api/manual-demo.postman_collection.json` and `demo-checks.sql`.
The collection contains 19 ordered requests, their bodies/headers, and expected responses.
There are no embedded credentials, JavaScript tests, or automatic ID-capture scripts.

## 1. Choose a demo tool

Recommended: **Bruno desktop for requests**, plus **psql inside Docker for database evidence**.
If you already know Postman, use it instead; the same supplied collection works there.

| Tool | Role | What is needed in this project? |
| --- | --- | --- |
| Bruno | Save and send ordered HTTP requests with Alice/Bob/admin tokens | Import the supplied Postman-format collection |
| Postman desktop | Same manual API walkthrough | Import the supplied collection |
| Swagger UI | Browse an OpenAPI contract and execute individual requests | Not configured yet; requires integration and security configuration |
| Docker psql | Inspect real PostgreSQL rows and run SQL | Already installed inside the database container |
| DBeaver Community | Optional graphical database browser | Install it and connect to localhost:5432 |

Swagger UI is an alternative API interface, not a database browser or concurrency proof. The manual
API client makes switching between Alice and Bob explicit. Python smoke/burst scripts remain useful
for repeatable verification; they do not need to be the main visual part of the interview demo.

Official import instructions:
- Postman: https://learning.postman.com/docs/getting-started/importing-and-exporting/importing-data
- Bruno: https://blog.usebruno.com/postman-to-bruno-migration-guide
- DBeaver download: https://dbeaver.io/download/
- DBeaver PostgreSQL connection: https://dbeaver.com/docs/dbeaver/Database-driver-PostgreSQL/

## 2. Prepare the running service

Keep Spring Boot running on port 8080. In a separate terminal, from the project root:

```sh
docker compose up -d db
curl http://localhost:8080/actuator/health/readiness
```

Expected: `{"status":"UP"}`. If the service is not running, follow the README startup steps.
A browser GET on the reserve URL is not a booking request: reserve requires POST and a token.

## 3. Import and set local variables

In Postman desktop: choose Import and select `docs/api/manual-demo.postman_collection.json`.
In Bruno desktop: choose Import Collection, select the Postman import option/file, and select this file.
Save any converted Bruno collection outside the Git project unless you intend to version it.

Create/select a local environment named `Local Demo` with these variables (or use Postman collection
variables). In Bruno, confirm these variables after import; recreate them if the importer did not
carry over collection variables. Variable names are case-sensitive.

| Variable | Value |
| --- | --- |
| `base_url` | `http://localhost:8080` (no trailing slash) |
| `admin_token` | Token minted for operator with admin scope |
| `alice_token` | Token minted for alice |
| `bob_token` | Token minted for bob |
| `show_id` | Initially blank; set from request 02 |
| `reservation_id` | Initially blank; set from Alice's request 04 |

Generate tokens once before the demo, using a terminal in the project root:

```sh
python3 scripts/mint_token.py operator --admin
python3 scripts/mint_token.py alice
python3 scripts/mint_token.py bob
```

Copy each printed token into the appropriate local variable. Do not include quotes or `Bearer` in
variable values: the collection already sets `Authorization: Bearer {{alice_token}}`, etc.
This helper only prepares credentials; all demonstrated requests are sent manually through the GUI.
Tokens expire after one hour. Regenerate them if authentication starts returning 401.
Do not put JWT_SECRET, DB_PASSWORD, or generated bearer tokens into a committed/shared collection.

## 4. Run the numbered requests manually

Click each request, show its method/URL/body, press Send, and explain its response. Do not run the
whole collection unattended: two IDs need to be copied from responses during the walkthrough.

| # | Action | Expected result | What to explain |
| --- | --- | --- | --- |
| 01 | Readiness | 200 UP | API and database dependency are ready |
| 02 | Create show | 201; five available seats | Admin creates fixed inventory and integer price |
| 03 | Initial inventory | 5 available, 0 confirmed | Capture the starting state |
| 04 | Alice books A1 and A2 | 201; 50000 paise; confirmed | Both seats booked atomically |
| 05 | Repeat same key/body | 200; same reservation ID | Retry creates nothing extra |
| 06 | Same key, different seats | 409 IDEMPOTENCY_KEY_REUSED | A key cannot mean two requests |
| 07 | Bob requests A1 | 409 SEAT_TAKEN | Existing ownership is protected |
| 08 | Bob requests A2 and A3 | 409 SEAT_TAKEN | A3 remains free: all-or-nothing |
| 09 | Alice requests A3,A4,A5 | 409 PER_USER_LIMIT | Existing 2 plus requested 3 exceeds 4 |
| 10 | Bob supplies body user_id=alice | 400 INVALID_REQUEST | Identity cannot be selected in a body |
| 11 | Inventory | 3 available, 2 confirmed | Declines did not allocate seats |
| 12 | Bob cancels Alice's reservation | 404 NOT_FOUND | Owner-only cancellation |
| 13 | Alice cancels | 200 cancelled | Releases both seats and user capacity |
| 14 | Inventory | 5 available, 0 confirmed | Release is visible after commit |
| 15 | Bob books released A1 | 201 | Fresh key because prior decline is retained |
| 16 | Repeat Alice's old cancellation | 200 cancelled | Cannot release Bob's new ownership |
| 17 | Final inventory | 4 available, 1 confirmed, total 5 | Reconciliation holds |
| 18 | Alice's current reservation | 200 cancelled | History remains after release |
| 19 | Metrics | 200 Prometheus text | Find this show ID's seat gauges |

**After request 02:** copy the response's `id` into `show_id`.
**After request 04:** copy `reservation_id` into the variable with that name.
**After request 15:** keep the variable pointing to ALICE'S original reservation. Do not replace it
with Bob's ID; request 16 must target the old cancellation.

Keys contain `{{show_id}}`, so a new show gives a fresh demo. For another rehearsal, start at request
02 again and replace both IDs. Do not expect request 04 to create a fresh booking when replaying the
same show/key after cancellation: it returns its original recorded outcome.

A successful replay returns 200 rather than another 201. The amount is a recorded price, not a real
payment charge; no payment provider is integrated.

## 5. Inspect PostgreSQL without installing an app

PostgreSQL's `psql` client is already in the container. From the project root:

```sh
docker compose exec db psql -U seats -d seats
```

You will see a prompt such as `seats=#`. Run:

```sql
\conninfo
\dt
\pset pager off
SELECT id, name, price_paise, per_user_limit
FROM shows WHERE name = 'interview-demo' ORDER BY created_at DESC LIMIT 1;
```

The listed ID must equal `show_id` in your API client. To inspect exactly that show's seats, replace
the placeholder below with its UUID:

```sql
SELECT s.seat_label,
       CASE WHEN s.reservation_id IS NULL THEN 'available' ELSE 'confirmed' END AS status,
       r.user_id, s.reservation_id
FROM show_seats s
LEFT JOIN reservations r ON r.id = s.reservation_id
WHERE s.show_id = 'PASTE-SHOW-UUID-HERE'::uuid
ORDER BY s.seat_label;
```

After the full flow: A1 belongs to bob; A2–A5 are available. `\q` exits psql.
The local container socket may not prompt for a password; GUI/TCP connections still use the password
from `.env`. A successful container-local login is not a test of your GUI password settings.

For all prepared checks, exit psql and run this from the project root:

```sh
docker compose exec -T db psql -U seats -d seats -v ON_ERROR_STOP=1 < docs/demo-checks.sql
```

The SQL file uses the **most recent show named interview-demo** and begins by printing its ID.
It runs in a read-only repeatable-read transaction, so all results share one snapshot. It selects
current ownership, historical reservations, current user usage, inventory counts, stored idempotency
results, and cumulative outcomes. Run it at step 11 and again after step 18 to compare.

Expected after the final step:

| Database evidence | Expected |
| --- | --- |
| show_seats | A1 owned by Bob; A2–A5 have NULL reservation_id |
| reservations + reservation_seats | Alice cancelled with A1,A2; Bob confirmed with A1 |
| user_show_usage | Alice 0; Bob 1; actual_owned_seats equals active_seat_count |
| Inventory aggregate | available 4, held 0, confirmed 1, total 5 |
| Original Alice idempotency record | One row, original status 201 and original reservation ID |

Do not count all history rows as current sales. A1 correctly appears in Alice's cancelled history
and Bob's active reservation. Current ownership is in show_seats; a released seat can be booked again.
Do not update database rows manually during the demo; demonstrate changes through the API.

## 6. Optional graphical database view with DBeaver

Download DBeaver Community for macOS Apple Silicon from the official download page. Open it and
create a new PostgreSQL connection with:

| Setting | Value |
| --- | --- |
| Host | localhost |
| Port | 5432 |
| Database | seats |
| Username | seats |
| Password | Actual DB_PASSWORD from the project's .env |

Allow the PostgreSQL JDBC driver download if prompted, then Test Connection and Finish.
Navigate to the connection → database seats → schema public → Tables. Open show_seats to inspect
current ownership, reservations for booking status, and reservation_seats for history.
Refresh the table view after each API mutation; an already-open result grid does not update itself.

Open `docs/demo-checks.sql` in a SQL editor associated with this connection and execute the script.
No psql backslash commands are used in that file, so it works in both clients. Confirm that the first
result matches your API show ID, especially if you have rehearsed more than once.

Your logs identify IntelliJ IDEA Community 2024.2.1, so do not depend on its having the database tools
available. Docker psql works immediately; DBeaver is an independent option.

## 7. Explain correctness and show concurrency

After the manual flow, open ReservationService.reserve(), lockUsage(), lockSeats(), and cancel().
Explain: the user row protects the limit, sorted seat locks protect allocation, the unique key protects
retries, and one transaction commits ownership/history/usage/idempotency together.

Manual clicks cannot prove concurrent safety. Run the burst separately after rehearsing it:

```sh
./burst.sh http://localhost:8080 --requests 500 --concurrency 500
```

It requires Python 3.10+. For the hot-seat phase expect 1 creation and 499 seat-taken declines, plus
zero 5xx/transport failures and a final PASS across the other scenarios. Show actual results only.
A 500-request local run is not proof of the full 20,000-request public deployment requirement.
The scripts' shows have different names, so they do not replace the latest interview-demo SQL target.

During the demo, keep application logs visible and correlate an X-Request-ID response header with
its log record. Metrics counters are cumulative, whereas seat gauges describe current inventory.
For the submission, repeat the flow against the public deployment URL and retain log evidence.
