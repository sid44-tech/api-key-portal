package io.github.apikeyportal.appaccess;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The server-wide switch for application read access. Exactly one row, id 1, seeded off. */
@Entity
@Table(name = "api_access_setting")
public class ApiAccessSetting {

    static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_by_email", length = 255)
    private String updatedByEmail;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ApiAccessSetting() {
        // for JPA
    }

    static ApiAccessSetting off() {
        ApiAccessSetting setting = new ApiAccessSetting();
        setting.id = SINGLETON_ID;
        return setting;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getUpdatedByEmail() {
        return updatedByEmail;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void change(boolean enabled, String byEmail, Instant at) {
        this.enabled = enabled;
        this.updatedByEmail = byEmail;
        this.updatedAt = at;
    }
}
