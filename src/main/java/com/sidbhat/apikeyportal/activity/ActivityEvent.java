package com.sidbhat.apikeyportal.activity;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.sidbhat.apikeyportal.user.UserAccount;

/**
 * One thing somebody did, kept for the history page.
 * <p>
 * Everything here is a flat snapshot rather than a set of associations, and there
 * are no foreign keys behind it. That is deliberate: an audit record has to stay
 * readable after the thing it describes is gone. A key created through an
 * integration should still say so a year later, when the key has been deleted, the
 * integration removed and the person who did it renamed or off-boarded.
 * <p>
 * Nothing secret is stored - names and ids only, never a key value.
 */
@Entity
@Table(name = "activity_event")
public class ActivityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ActivityAction action;

    /** Who did it. Plain id, not a relation, so the row survives the account. */
    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", nullable = false, length = 100)
    private String actorName;

    @Column(name = "actor_email", nullable = false, length = 255)
    private String actorEmail;

    /** Whose history this belongs to. Null means only super admins see it. */
    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "group_name", length = 100)
    private String groupName;

    /** The key, integration, invitation or group that was acted on. */
    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "target_name", nullable = false, length = 300)
    private String targetName;

    /** Set on key creations only. */
    @Enumerated(EnumType.STRING)
    @Column(name = "key_source", length = 20)
    private KeySource keySource;

    /** The integration or provider a key came through, where there was one. */
    @Column(length = 300)
    private String detail;

    protected ActivityEvent() {
        // for JPA
    }

    public ActivityEvent(ActivityAction action, UserAccount actor, Long targetId, String targetName,
            KeySource keySource, String detail, Instant occurredAt) {
        this.action = action;
        this.actorId = actor.getId();
        this.actorName = actor.getDisplayName();
        this.actorEmail = actor.getEmail();
        this.groupId = actor.getGroupId();
        this.groupName = actor.getGroup() == null ? null : actor.getGroup().getName();
        this.targetId = targetId;
        this.targetName = targetName;
        this.keySource = keySource;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public ActivityAction getAction() {
        return action;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public Long getGroupId() {
        return groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public Long getTargetId() {
        return targetId;
    }

    public String getTargetName() {
        return targetName;
    }

    public KeySource getKeySource() {
        return keySource;
    }

    public String getDetail() {
        return detail;
    }

    /** True if the target is still worth linking to from the history page. */
    public boolean isKeyEvent() {
        return action == ActivityAction.KEY_CREATED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ActivityEvent)) {
            return false;
        }
        ActivityEvent other = (ActivityEvent) o;
        return id != null && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "ActivityEvent{id=" + id + ", action=" + action + ", actor=" + actorEmail
                + ", target='" + targetName + "'}";
    }
}
