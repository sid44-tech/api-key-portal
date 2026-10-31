package com.sidbhat.apikeyportal.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserGroupRepository extends JpaRepository<UserGroup, Long> {

    Optional<UserGroup> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<UserGroup> findAllByOrderByNameAsc();
}
