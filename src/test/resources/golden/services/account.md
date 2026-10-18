---
service: account
repo: account
path: account-service
exposes: 2
calls: 0
publishes: 1
consumes: 0
tables: 1
findings: 0
---

# account

Repo `account`, module `account-service`.

## Exposes

| Method | Path | Called by | Reaches | Source | Confidence | Evidence |
|---|---|---|---|---|---|---|
| GET | `/api/accounts/{id}` | gateway | - | controller | EXACT | [account/account-service/target/generated-sources/openapi/src/main/java/demo/account/web/api/AccountsApi.java:17](../../../src/test/resources/fleet/account/account-service/target/generated-sources/openapi/src/main/java/demo/account/web/api/AccountsApi.java#L17) |
| POST | `/api/accounts` | gateway | `account.created` | controller | EXACT | [account/account-service/target/generated-sources/openapi/src/main/java/demo/account/web/api/AccountsApi.java:22](../../../src/test/resources/fleet/account/account-service/target/generated-sources/openapi/src/main/java/demo/account/web/api/AccountsApi.java#L22) |

## Calls

_None._

## Publishes

| Topic | Consumers | Via | Confidence | Evidence |
|---|---|---|---|---|
| `account.created` | notification | KafkaTemplate | RESOLVED | [account/account-service/src/main/java/demo/account/events/AccountEvents.java:17](../../../src/test/resources/fleet/account/account-service/src/main/java/demo/account/events/AccountEvents.java#L17)<br>[account/account-api/src/main/java/demo/account/api/Topics.java:4](../../../src/test/resources/fleet/account/account-api/src/main/java/demo/account/api/Topics.java#L4)<br>[account/account-api/src/main/java/demo/account/api/Topics.java:5](../../../src/test/resources/fleet/account/account-api/src/main/java/demo/account/api/Topics.java#L5) |

## Consumes

_None._

## Data

| Table | Store | Relation | Confidence | Evidence |
|---|---|---|---|---|
| `accounts` | sql | owns | EXACT | [account/account-service/src/main/resources/db/changelog/db.changelog-master.yaml:7](../../../src/test/resources/fleet/account/account-service/src/main/resources/db/changelog/db.changelog-master.yaml#L7) |
| `accounts` | sql | uses | EXACT | [account/account-service/src/main/java/demo/account/data/AccountEntity.java:7](../../../src/test/resources/fleet/account/account-service/src/main/java/demo/account/data/AccountEntity.java#L7)<br>[account/account-service/src/main/java/demo/account/data/AccountRepository.java:6](../../../src/test/resources/fleet/account/account-service/src/main/java/demo/account/data/AccountRepository.java#L6) |

## Findings

_None._
