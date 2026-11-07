package io.github.apikeyportal.web;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** What the new-token form collects. Limits are enforced again in the service. */
public class AppTokenForm {

    /** The choices offered; the service accepts anything from 1 to 365. */
    public static final List<Integer> VALIDITY_CHOICES = List.of(30, 90, 180, 365);

    @NotBlank(message = "Give the token a name, e.g. the app that will use it")
    @Size(max = 100)
    private String name;

    @NotNull
    private Long groupId;

    @NotNull
    private Integer validDays = 90;

    @NotEmpty(message = "Choose at least one key")
    private List<Long> keyIds = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public Integer getValidDays() {
        return validDays;
    }

    public void setValidDays(Integer validDays) {
        this.validDays = validDays;
    }

    public List<Long> getKeyIds() {
        return keyIds;
    }

    public void setKeyIds(List<Long> keyIds) {
        this.keyIds = keyIds == null ? new ArrayList<>() : keyIds;
    }
}
