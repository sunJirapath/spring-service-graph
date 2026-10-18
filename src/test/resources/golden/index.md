# Fleet overview

4 services, 5 endpoints, 2 topics, 1 tables, 2 findings.

## Services

| Service | Repo | Module | Exposes | Calls | Publishes | Consumes | Findings |
|---|---|---|---|---|---|---|---|
| [account](services/account.md) | account | `account-service` | 2 | 0 | 1 | 0 | 0 |
| [account-admin](services/account-admin.md) | account | `account-admin` | 1 | 0 | 0 | 0 | 0 |
| [gateway](services/gateway.md) | gateway | `.` | 1 | 6 | 0 | 0 | 2 |
| [notification](services/notification.md) | notification | `.` | 1 | 0 | 0 | 2 | 0 |

## Service dependencies

| From → To | Clients |
|---|---|
| gateway → account | feign, gateway |
| gateway → notification | http-exchange |
| gateway → unresolved | rest-template |

## Topics

| Topic | Producers | Consumers |
|---|---|---|
| `account.created` | account | notification |
| `audit.events` | - | notification |

## Findings

| Kind | Count |
|---|---|
| CONTRACT_DRIFT | 1 |
| UNRESOLVED | 1 |

- **CONTRACT_DRIFT** `gateway`: feign call POST /api/accounts/{id}/close matches no endpoint exposed by account — [gateway/src/main/java/demo/gateway/client/AccountClient.java:14](../../src/test/resources/fleet/gateway/src/main/java/demo/gateway/client/AccountClient.java#L14)
- **UNRESOLVED** `gateway`: rest-template call GET {?}/reports/{?} (from `baseUrlFor(region) + "/reports/" + id`) — [gateway/src/main/java/demo/gateway/report/LegacyReportClient.java:16](../../src/test/resources/fleet/gateway/src/main/java/demo/gateway/report/LegacyReportClient.java#L16)
