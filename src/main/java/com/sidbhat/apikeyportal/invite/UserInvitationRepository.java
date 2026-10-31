package com.sidbhat.apikeyportal.invite;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserInvitationRepository extends JpaRepository<UserInvitation, Long> {

    Optional<UserInvitation> findByTokenHash(String tokenHash);

    List<UserInvitation> findAllByOrderByCreatedAtDesc();

    List<UserInvitation> findByGroup_IdOrderByCreatedAtDesc(Long groupId);

    /** Every invitation ever sent to an address, newest first. */
    List<UserInvitation> findByEmailIgnoreCaseOrderByCreatedAtDesc(String email);

    /**
     * Invitations into a group that could still be accepted. Used to stop the group
     * being deleted out from under them - the row points at it by id.
     */
    @Query("select count(i) from UserInvitation i where i.group.id = :groupId "
            + "and i.acceptedAt is null and i.revokedAt is null and i.expiresAt > :now")
    long countPendingInGroup(@Param("groupId") Long groupId, @Param("now") Instant now);
}
