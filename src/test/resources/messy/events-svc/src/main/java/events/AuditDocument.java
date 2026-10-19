package events;

@Document(collection = "audit_events")
class AuditDocument {}

interface AuditRepository extends MongoRepository<AuditDocument, String> {}
