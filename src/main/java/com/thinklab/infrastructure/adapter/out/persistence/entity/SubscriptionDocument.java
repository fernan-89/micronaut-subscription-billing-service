package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.Subscription;
import com.thinklab.domain.model.Subscription.SubscriptionStatus;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Infrastructure-specific representation of the Subscription aggregate for MongoDB. Top-level {@code public} on purpose (see
 * {@link PlanDocument}).
 */
@Introspected
public class SubscriptionDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private String planCode;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getPlanCode() { return planCode; }
    public void setPlanCode(String planCode) { this.planCode = planCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    /** Strict isolation between Document and Domain. */
    public static final class SubscriptionPersistenceMapper {

        private SubscriptionPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static SubscriptionDocument toDocument(Subscription subscription) {
            SubscriptionDocument doc = new SubscriptionDocument();
            doc.setId(subscription.getId());
            doc.setOrganisationId(subscription.getOrganisationId());
            doc.setPlanCode(subscription.getPlanCode());
            doc.setStatus(subscription.getStatus().name());
            doc.setCreatedAt(subscription.getCreatedAt());
            doc.setUpdatedAt(subscription.getUpdatedAt());
            doc.setAuditTrail(subscription.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static Subscription toDomain(SubscriptionDocument doc) {
            return Subscription.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getPlanCode(), SubscriptionStatus.valueOf(doc.getStatus()),
                    doc.getCreatedAt(), doc.getUpdatedAt(), doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).toList());
        }
    }
}
