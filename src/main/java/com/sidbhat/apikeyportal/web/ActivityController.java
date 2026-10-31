package com.sidbhat.apikeyportal.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.sidbhat.apikeyportal.activity.ActivityService;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountService;

/**
 * The history of what people have created, for administrators.
 * <p>
 * A group admin sees their own group; a super admin sees every group. Members
 * don't see it at all - the route is restricted in {@code SecurityConfig} and the
 * service checks again, so neither alone is load-bearing.
 */
@Controller
@RequestMapping("/activity")
public class ActivityController {

    /** Long enough to scan a week of activity without paging. */
    static final int PAGE_SIZE = 40;

    private final ActivityService activityService;
    private final UserAccountService userService;

    public ActivityController(ActivityService activityService, UserAccountService userService) {
        this.activityService = activityService;
        this.userService = userService;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal PortalUser principal,
            @RequestParam(value = "page", defaultValue = "0") int page, Model model) {
        UserAccount me = userService.getCurrent(principal);
        model.addAttribute("page", activityService.findVisibleTo(me,
                PageRequest.of(Math.max(page, 0), PAGE_SIZE)));
        model.addAttribute("currentUser", me);
        return "activity/list";
    }
}
