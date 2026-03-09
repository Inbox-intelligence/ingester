# InboxIntelligence Ingester — Architecture

```
                        +-----------------------+
                        |   External Systems    |
                        +-----------------------+
                        |                       |
              Google OAuth 2.0        Google Cloud Pub/Sub
                        |                       |
                        v                       v
 +--------------------------------------------------------------+
 |  inbound                                                      |
 |                                                               |
 |  GmailApiController  [@RestController /gmail-api]             |
 |    GET /login           ──> redirects to OAuth consent URL    |
 |    GET /token-callback  ──> exchanges auth code for tokens    |
 |                                                               |
 |  GmailPubSubSubscriber  [@Component]                          |
 |    listens to Pub/Sub push notifications                      |
 |    dispatches to GmailSyncService                             |
 +--------------------------------------------------------------+
                        |
                        v
 +--------------------------------------------------------------+
 |  domain                                                       |
 |                                                               |
 |  GmailOAuthLoginService                                       |
 |    builds OAuth consent URL                                   |
 |                                                               |
 |  GmailTokenService                                            |
 |    exchanges auth code -> tokens                              |
 |    sets up Gmail watch (via GmailApiClient)                   |
 |                                                               |
 |  GmailSyncService  (orchestrator)                             |
 |    concurrency control + sync loop                            |
 |         |              |              |                       |
 |         v              v              v                       |
 |  GmailMimeContent  fetchAttach-  EmailContent                 |
 |    Extractor        mentData     StorageService               |
 |    walks MIME tree   resolves     stores to disk              |
 |    extracts text/    bytes via    saves DB record             |
 |    html + parts      ApiClient                                |
 +--------------------------------------------------------------+
                        |                                |
                        v                                v
 +--------------------------------------------------------------+
 |  outbound                                                     |
 |                                                               |
 |  GmailApiClient  [@Retry]                                    |
 |    fetchHistory(...)      ──>  Gmail history.list             |
 |    fetchMessage(...)      ──>  Gmail messages.get             |
 |    fetchAttachment(...)   ──>  Gmail attachments.get          |
 |    watchMailbox(...)      ──>  Gmail users.watch              |
 |                                                               |
 |  GmailClientFactory                                           |
 |    creates Gmail client instances (OAuth + refresh token)     |
 +--------------------------------------------------------------+
                        |
                        v
                   Gmail API
```

## Persistence Layer

```
 +--------------------------------------------------------------+
 |  persistence                                                  |
 |                                                               |
 |  GmailMailboxService  ──>  GmailMailboxRepository  ──>  DB   |
 |  EmailContentService  ──>  EmailContentRepository  ──>  DB   |
 |  EmailAttachmentService ──> EmailAttachmentRepository ──> DB  |
 +--------------------------------------------------------------+
```

## Support

```
 +--------------------------------------------------------------+
 |  config                           utils                       |
 |                                                               |
 |  GmailApiProperties               Base64Utils                 |
 |  EmailContentStorageProperties     JsonUtils                  |
 +--------------------------------------------------------------+
```

## Dependency Flow

```
 inbound  ──>  domain  ──>  outbound  ──>  Gmail API
                  |
                  +──>  persistence  ──>  PostgreSQL
                  |
                  +──>  local file system
```

## Package Structure

```
com.inboxintelligence.ingester/
├── IngesterApplication.java
├── config/
│   ├── GmailApiProperties.java
│   └── EmailContentStorageProperties.java
├── inbound/
│   ├── GmailApiController.java
│   └── GmailPubSubSubscriber.java
├── domain/
│   ├── GmailSyncService.java
│   ├── GmailMimeContentExtractor.java
│   ├── EmailContentStorageService.java
│   ├── GmailTokenService.java
│   └── GmailOAuthLoginService.java
├── outbound/
│   ├── GmailApiClient.java
│   └── GmailClientFactory.java
├── model/
│   ├── GmailEvent.java
│   ├── SyncStatus.java
│   └── entity/
│       ├── GmailMailbox.java
│       ├── EmailContent.java
│       └── EmailAttachment.java
├── persistence/
│   ├── repository/
│   │   ├── GmailMailboxRepository.java
│   │   ├── EmailContentRepository.java
│   │   └── EmailAttachmentRepository.java
│   └── service/
│       ├── GmailMailboxService.java
│       ├── EmailContentService.java
│       └── EmailAttachmentService.java
├── exception/
│   └── RetryableGmailApiException.java
└── utils/
    ├── Base64Utils.java
    └── JsonUtils.java
```

## Key Design Decisions

- **GmailSyncService** is the orchestrator — fetches, parses, resolves attachments, delegates storage
- **EmailContentStorageService** has zero Gmail dependency — receives pre-resolved bytes only
- **GmailApiClient** is the sole Gmail API gateway — all calls wrapped with `@Retry`
- **GmailClientFactory** lives in `outbound` — creates `Gmail` client instances (pure integration concern)
- **GmailPubSubSubscriber** lives in `inbound` — it's an entry point, not domain logic
- **GmailMimeContentExtractor** stays in `domain` — it's a stateless parser with domain knowledge
- **Resilience4j** retries only `RetryableGmailApiException` (not all RuntimeExceptions)
