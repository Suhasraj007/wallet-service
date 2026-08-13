# Deploying for free — Render + Neon + Grafana Cloud

End state: a public app URL on Render, Postgres on Neon, and a public Grafana
dashboard link showing live logs. Total cost: ₹0, no card required anywhere.

Do the steps in this order — Neon and Grafana first, so all environment
variables exist before the Render service boots.

## 1. Neon (free Postgres)

1. Sign up at https://neon.tech (no card).
2. Create a project. Pick the AWS **Singapore (ap-southeast-1)** region so the
   database sits near the Render Singapore region used below.
3. On the project dashboard, open **Connect** and copy the connection details.
   Use the **direct** host (the one *without* `-pooler` in it) — this app
   manages its own small connection pool, and Flyway prefers a direct
   connection.
4. Build the three values the app needs:
   - `SPRING_DATASOURCE_URL` = `jdbc:postgresql://<host>/<database>?sslmode=require`
   - `SPRING_DATASOURCE_USERNAME` = the role name
   - `SPRING_DATASOURCE_PASSWORD` = the password

Neon's free plan scales compute to zero after ~5 minutes idle and wakes in
under a second on the next query, so it stays inside the free 100
compute-hours per month without any babysitting.

## 2. Grafana Cloud (free, for the public logs link)

1. Sign up at https://grafana.com (free tier, no card). A stack is provisioned
   for you.
2. Find your Loki details: **Connections → Data sources → Loki** (the
   pre-provisioned one). Note the URL, which looks like
   `https://logs-prod-XXX.grafana.net`, and the numeric **user** id.
3. Create a write token: **Administration → Users and access → Cloud access
   policies → Create access policy**, scope **logs:write**, then create a
   token inside it and copy it once.
4. The three values the app needs:
   - `LOKI_URL` = `<loki url>/loki/api/v1/push`
   - `LOKI_USERNAME` = the numeric user id
   - `LOKI_PASSWORD` = the token

## 3. Render (free web service, container deploy)

1. Sign up at https://render.com (no card) and connect your GitHub account.
2. **New → Web Service**, pick the repo. Render detects the `Dockerfile` and
   deploys the container image it builds — this is a container deploy, not a
   buildpack.
3. Instance type: **Free**. Region: **Singapore**.
4. Set **Health Check Path** to `/healthz`.
5. Environment variables:

   | Name | Value |
   | ---- | ----- |
   | `SPRING_DATASOURCE_URL` | from Neon step |
   | `SPRING_DATASOURCE_USERNAME` | from Neon step |
   | `SPRING_DATASOURCE_PASSWORD` | from Neon step |
   | `JWT_SECRET` | generate locally: `openssl rand -base64 48` |
   | `SPRING_PROFILES_ACTIVE` | `json,loki` |
   | `LOKI_URL` | from Grafana step |
   | `LOKI_USERNAME` | from Grafana step |
   | `LOKI_PASSWORD` | from Grafana step |
   | `LOKI_ENV` | `prod` |

6. Create the service. First build takes a few minutes. Then verify:

   ```bash
   curl -s https://<service>.onrender.com/healthz
   curl -s https://<service>.onrender.com/readyz
   ```

Free instances spin down after 15 minutes without traffic and take up to a
minute to wake. Two mitigations: the burst script already waits for wake-up,
and step 5 below keeps it warm while it is being evaluated.

## 4. The public logs link

1. In Grafana, open **Explore**, pick the Loki data source, and run the query
   `{app="wallet-service"}`. Hit the deployed app once or twice — log lines
   should appear within a few seconds (loki4j batches for up to 5 s).
2. Build the dashboard: **Dashboards → New → New dashboard → Add
   visualization**, Loki data source:
   - Panel 1 — **Logs** visualization, query `{app="wallet-service"}`.
   - Panel 2 — **Time series**, query
     `sum(count_over_time({app="wallet-service"} |= "transfer_applied" [1m]))`
     titled "Transfers applied/min".
   - Panel 3 — same shape with `transfer_rejected_insufficient_funds`, and
     optionally `idempotent_replay` and `auth_failure`.
   Keep queries free of template variables — externally shared dashboards do
   not support them.
3. Save the dashboard, then **Share → Shared dashboard** (Grafana's external
   "public dashboard" sharing), enable it, and copy the public URL. Open it in
   an incognito window to confirm it loads without login. If the Logs panel
   ever refuses to render in the shared view, switch that panel's
   visualization to **Table** with the same query — tables are always
   supported.

That public URL is the "logs link" to send back.

For metrics: `GET /metrics` on the app is already public Prometheus text with
request counts, p99 latency histograms, and the business counters. If you also
want it charted, Grafana Cloud's Metrics Endpoint integration can scrape a
public HTTPS `/metrics` URL — optional, the endpoint itself already satisfies
the requirement.

## 5. Keep it warm during evaluation

Create a free monitor at https://cron-job.org (or UptimeRobot) that GETs
`https://<service>.onrender.com/healthz` every 10 minutes. This keeps the
Render free instance from sleeping while it is being probed, and doubles as
uptime monitoring. Well within Render's 750 free instance hours per month.

## 6. Final verification

```bash
./scripts/burst.sh https://<service>.onrender.com
```

Expect the PASS table: every distinct-key transfer applied once, all same-key
retries returning one identical outcome, balances reconciling exactly, zero
5xx. Then open the public dashboard link and watch the burst's
`transfer_applied` and `idempotent_replay` events, each carrying its
`correlation_id`.

## Cost note

| Piece | Plan | Cost |
| ----- | ---- | ---- |
| Render web service | Free instance (750 h/month, sleeps when idle) | ₹0 |
| Neon Postgres | Free plan (0.5 GB, 100 compute-hours/month, scale-to-zero) | ₹0 |
| Grafana Cloud (Loki + dashboards) | Free tier | ₹0 |
| cron-job.org keep-warm ping | Free | ₹0 |

No card was required for any account.
