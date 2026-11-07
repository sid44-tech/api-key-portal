package io.github.apikeyportal.appaccess;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One key a token may read. The grant's id is the reference an app is
 * configured with; {@link #getKeyId()} moves forward as the key is rotated, so
 * the reference keeps pointing at the current value.
 */
@Entity
@Table(name = "app_access_grant")
public class AppAccessGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "token_id", nullable = false)
    private AppAccessToken token;

    @Column(name = "key_id", nullable = false)
    private Long keyId;

    /** Which key the app last read through this grant: a new one means a rotation reached it. */
    @Column(name = "last_read_key_id")
    private Long lastReadKeyId;

    @Column(name = "last_read_at")
    private Instant lastReadAt;

    protected AppAccessGrant() {
        // for JPA
    }

    AppAccessGrant(AppAccessToken token, Long keyId) {
        this.token = token;
        this.keyId = keyId;
    }

    public Long getId() {
        return id;
    }

    public Long getKeyId() {
        return keyId;
    }

    void followTo(Long keyId) {
        this.keyId = keyId;
    }

    public Long getLastReadKeyId() {
        return lastReadKeyId;
    }

    public Instant getLastReadAt() {
        return lastReadAt;
    }

    void markRead(Long keyId, Instant at) {
        this.lastReadKeyId = keyId;
        this.lastReadAt = at;
    }
}
