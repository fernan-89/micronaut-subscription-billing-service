package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.Plan;
import com.thinklab.domain.model.Plan.PlanStatus;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Infrastructure-specific representation of the Plan aggregate for MongoDB. Must be a top-level {@code public} class: a
 * package-private BSON entity passes every mocked test but fails on the first real write (the POJO codec never calls
 * {@code setAccessible(true)}).
 */
@Introspected
public class PlanDocument {

    @BsonId
    private UUID id;

    private String code;
    private String name;
    private Map<String, Long> entitlements = new LinkedHashMap<>();
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Map<String, Long> getEntitlements() { return entitlements; }
    public void setEntitlements(Map<String, Long> entitlements) { this.entitlements = entitlements; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    /** Strict isolation between Document and Domain. */
    public static final class PlanPersistenceMapper {

        private PlanPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static PlanDocument toDocument(Plan plan) {
            PlanDocument doc = new PlanDocument();
            doc.setId(plan.getId());
            doc.setCode(plan.getCode());
            doc.setName(plan.getName());
            doc.setEntitlements(new LinkedHashMap<>(plan.getEntitlements()));
            doc.setStatus(plan.getStatus().name());
            doc.setCreatedAt(plan.getCreatedAt());
            doc.setUpdatedAt(plan.getUpdatedAt());
            doc.setAuditTrail(plan.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static Plan toDomain(PlanDocument doc) {
            return Plan.reconstitute(doc.getId(), doc.getCode(), doc.getName(), doc.getEntitlements(), PlanStatus.valueOf(doc.getStatus()),
                    doc.getCreatedAt(), doc.getUpdatedAt(), doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).toList());
        }
    }
}
