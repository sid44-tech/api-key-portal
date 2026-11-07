package io.github.apikeyportal.user;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** How an SSO sign-in finds the account it belongs to. */
    Optional<UserAccount> findBySsoSubject(String ssoSubject);

    List<UserAccount> findAllByOrderByDisplayNameAsc();

    List<UserAccount> findByGroup_IdOrderByDisplayNameAsc(Long groupId);

    long countByGroup_Id(Long groupId);

    /** Who gets told when something about a key in this group needs an admin: super admins and its group admins. */
    @Query("select u from UserAccount u where u.role = io.github.apikeyportal.user.UserRole.SUPER_ADMIN "
            + "or (u.role = io.github.apikeyportal.user.UserRole.GROUP_ADMIN and u.group = :group)")
    List<UserAccount> findAdminsFor(@Param("group") UserGroup group);

    /** How many accounts each group holds, as {@code [groupId, count]} rows. */
    @Query("select u.group.id, count(u) from UserAccount u where u.group is not null group by u.group.id")
    List<Object[]> countByGroup();

    /**
     * Bulk update rather than dirty checking so that signing in doesn't bump updated_at.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserAccount u set u.lastLoginAt = :loggedInAt where u.id = :id")
    int recordLogin(@Param("id") Long id, @Param("loggedInAt") Instant loggedInAt);
}
