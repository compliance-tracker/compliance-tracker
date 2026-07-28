package com.chrainx.compliance_tracker.business;

import jakarta.persistence.*;

// Records which business a given (idempotencyKey, owner) pair already created (issue #61) - a
// client-supplied key (typically a UUID it generates once per logical create attempt) let
// BusinessController recognize a retried request and return the original business instead of
// creating a duplicate. Deliberately its own small table, not a column on Business - a business
// only optionally has an idempotency key (most requests won't send one), and the real
// enforcement point is the unique constraint on (idempotency_key, owner_id) at the DB level, see
// V4 migration.
@Entity
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key")
    private String key;

    private Long ownerId;

    private Long businessId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public Long getBusinessId() { return businessId; }
    public void setBusinessId(Long businessId) { this.businessId = businessId; }
}
