package io.github.apikeyportal.appaccess;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiAccessSettingRepository extends JpaRepository<ApiAccessSetting, Long> {
}
