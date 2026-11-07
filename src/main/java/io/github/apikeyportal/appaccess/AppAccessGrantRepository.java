package io.github.apikeyportal.appaccess;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppAccessGrantRepository extends JpaRepository<AppAccessGrant, Long> {

    /** Moves grants off a key being deleted and onto the key that replaced it. */
    @Modifying(flushAutomatically = true)
    @Query("update AppAccessGrant g set g.keyId = :to where g.keyId = :from")
    int moveToKey(@Param("from") Long from, @Param("to") Long to);

    @Modifying(flushAutomatically = true)
    @Query("delete from AppAccessGrant g where g.keyId = :keyId")
    int deleteByKeyId(@Param("keyId") Long keyId);
}
