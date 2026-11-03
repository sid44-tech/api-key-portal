package com.sidbhat.apikeyportal.web;

import java.time.LocalDate;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.sidbhat.apikeyportal.key.ApiKeyService;
import com.sidbhat.apikeyportal.key.ExpirationStatus;
import com.sidbhat.apikeyportal.key.KeyDashboard;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountService;
import com.sidbhat.apikeyportal.user.UserGroup;

/**
 * What needs attention in a group's keys: expiring soon, failed automatic
 * replacements, and keys nobody has ever revealed. Everyone sees it for their
 * own group - it is the same metadata as the key list, and no value is ever on
 * it - and a super admin can look at any group, or all of them.
 */
@Controller
@RequestMapping("/dashboard")
public class DashboardController {

    private final ApiKeyService apiKeyService;
    private final UserAccountService userService;

    public DashboardController(ApiKeyService apiKeyService, UserAccountService userService) {
        this.apiKeyService = apiKeyService;
        this.userService = userService;
    }

    /**
     * @param groupId a super admin's choice of group; blank means every group.
     *                Ignored for anyone else, whose scope is their own group.
     */
    @GetMapping
    public String dashboard(@RequestParam(value = "group", required = false) Long groupId,
            @AuthenticationPrincipal PortalUser principal, Model model) {
        UserAccount me = userService.getCurrent(principal);
        UserGroup selected;
        if (me.isSuperAdmin()) {
            // a wrong id is a 404 from getGroup, not a silent "all groups"
            selected = groupId == null ? null : userService.getGroup(groupId);
            model.addAttribute("groups", userService.findAllGroups());
        } else {
            selected = me.getGroup();
        }
        KeyDashboard dashboard = apiKeyService.dashboard(me, selected == null ? null : selected.getId(),
                LocalDate.now());
        model.addAttribute("dashboard", dashboard);
        model.addAttribute("selectedGroup", selected);
        // an expiring key with a replacement already minted is a smaller worry
        model.addAttribute("replacements", apiKeyService.findReplacements(dashboard.expiringSoon()));
        model.addAttribute("expiringSoonDays", ExpirationStatus.EXPIRING_SOON_DAYS);
        model.addAttribute("unusedAfterDays", KeyDashboard.UNUSED_AFTER_DAYS);
        model.addAttribute("currentUser", me);
        return "dashboard/index";
    }
}
