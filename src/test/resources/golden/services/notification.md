---
service: notification
repo: notification
path: .
exposes: 1
calls: 0
publishes: 0
consumes: 2
tables: 0
findings: 0
---

# notification

Repo `notification`, module `.`.

## Exposes

| Method | Path | Called by | Reaches | Source | Confidence | Evidence |
|---|---|---|---|---|---|---|
| POST | `/notifications` | gateway | - | controller | EXACT | [notification/src/main/java/demo/notification/web/NotificationController.java:12](../../../src/test/resources/fleet/notification/src/main/java/demo/notification/web/NotificationController.java#L12)<br>[notification/src/main/java/demo/notification/web/NotificationController.java:13](../../../src/test/resources/fleet/notification/src/main/java/demo/notification/web/NotificationController.java#L13) |

## Calls

_None._

## Publishes

_None._

## Consumes

| Topic | Producers | Via | Confidence | Evidence |
|---|---|---|---|---|
| `account.created` | account | KafkaListener | RESOLVED | [notification/src/main/java/demo/notification/events/AccountCreatedListener.java:9](../../../src/test/resources/fleet/notification/src/main/java/demo/notification/events/AccountCreatedListener.java#L9) |
| `audit.events` | - | cloud-stream:audit-in-0 | EXACT | [notification/src/main/resources/application.yml:8](../../../src/test/resources/fleet/notification/src/main/resources/application.yml#L8) |

## Data

_None._

## Findings

_None._
