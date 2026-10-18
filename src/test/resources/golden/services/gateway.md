---
service: gateway
repo: gateway
path: .
exposes: 1
calls: 6
publishes: 0
consumes: 0
tables: 0
findings: 2
---

# gateway

Repo `gateway`, module `.`.

## Exposes

| Method | Path | Called by | Source | Confidence | Evidence |
|---|---|---|---|---|---|
| * | `/account-api/**` | - | gateway-route | EXACT | [gateway/src/main/resources/application.yml:12](../../../src/test/resources/fleet/gateway/src/main/resources/application.yml#L12) |

## Calls

| Target | Method | Path | Client | Matched | Confidence | Evidence |
|---|---|---|---|---|---|---|
| account | GET | `/api/accounts/{id}` | feign | `GET /api/accounts/{id}` | EXACT | [gateway/src/main/java/demo/gateway/client/AccountClient.java:11](../../../src/test/resources/fleet/gateway/src/main/java/demo/gateway/client/AccountClient.java#L11) |
| account | * | `/**` | gateway | `GET /api/accounts/{id}` | EXACT | [gateway/src/main/resources/application.yml:10](../../../src/test/resources/fleet/gateway/src/main/resources/application.yml#L10) |
| account | * | `/**` | gateway | `POST /api/accounts` | EXACT | [gateway/src/main/resources/application.yml:10](../../../src/test/resources/fleet/gateway/src/main/resources/application.yml#L10) |
| notification | POST | `/notifications` | http-exchange | `POST /notifications` | RESOLVED | [gateway/src/main/java/demo/gateway/client/NotificationClient.java:10](../../../src/test/resources/fleet/gateway/src/main/java/demo/gateway/client/NotificationClient.java#L10)<br>[gateway/src/main/resources/application.yml:17](../../../src/test/resources/fleet/gateway/src/main/resources/application.yml#L17) |
| unresolved | GET | `{?}/reports/{?}` | rest-template | - | UNRESOLVED | [gateway/src/main/java/demo/gateway/report/LegacyReportClient.java:16](../../../src/test/resources/fleet/gateway/src/main/java/demo/gateway/report/LegacyReportClient.java#L16) |
| account | POST | `/api/accounts/{id}/close` | feign | **no matching endpoint** | EXACT | [gateway/src/main/java/demo/gateway/client/AccountClient.java:14](../../../src/test/resources/fleet/gateway/src/main/java/demo/gateway/client/AccountClient.java#L14) |

## Publishes

_None._

## Consumes

_None._

## Data

_None._

## Findings

- **CONTRACT_DRIFT** feign call POST /api/accounts/{id}/close matches no endpoint exposed by account — [gateway/src/main/java/demo/gateway/client/AccountClient.java:14](../../../src/test/resources/fleet/gateway/src/main/java/demo/gateway/client/AccountClient.java#L14)
- **UNRESOLVED** rest-template call GET {?}/reports/{?} (from `baseUrlFor(region) + "/reports/" + id`) — [gateway/src/main/java/demo/gateway/report/LegacyReportClient.java:16](../../../src/test/resources/fleet/gateway/src/main/java/demo/gateway/report/LegacyReportClient.java#L16)
