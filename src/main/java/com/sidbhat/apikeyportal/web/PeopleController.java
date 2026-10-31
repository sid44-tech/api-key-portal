package com.sidbhat.apikeyportal.web;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.sidbhat.apikeyportal.invite.InvitationService;
import com.sidbhat.apikeyportal.key.ApiKeyService;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccount;
import com.sidbhat.apikeyportal.user.UserAccountService;
import com.sidbhat.apikeyportal.user.UserRole;

/**
 * Who else is in the portal. A member sees the rest of their group, a group admin
 * sees and administers it, and a super admin sees every account there is.
 */
@Controller
@RequestMapping("/people")
public class PeopleController {

    private final UserAccountService userService;
    private final ApiKeyService apiKeyService;

    public PeopleController(UserAccountService userService, ApiKeyService apiKeyService) {
        this.userService = userService;
        this.apiKeyService = apiKeyService;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal PortalUser principal, Model model) {
        UserAccount me = userService.getCurrent(principal);
        List<UserAccount> people = userService.findVisibleTo(me);
        Map<Long, Long> keyCounts = apiKeyService.countByOwner(people.stream().map(UserAccount::getId).toList());
        model.addAttribute("people", people);
        model.addAttribute("keyCounts", keyCounts);
        model.addAttribute("currentUser", me);
        model.addAttribute("canInvite", InvitationService.canInvite(me));
        return "people/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal, Model model) {
        UserAccount me = userService.getCurrent(principal);
        UserAccount person = userService.getVisible(id, me);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", UserAdminForm.from(person));
        }
        prepareDetailModel(model, me, person);
        return "people/detail";
    }

    @PostMapping("/{id}")
    public String administer(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal,
            @Valid @ModelAttribute("form") UserAdminForm form, BindingResult bindingResult, Model model,
            RedirectAttributes redirectAttributes) {
        UserAccount me = userService.getCurrent(principal);
        UserAccount person = userService.getVisible(id, me);
        if (!bindingResult.hasErrors()) {
            try {
                userService.administer(id, me, form.getRole(), form.getGroupId(), form.isEnabled());
                redirectAttributes.addFlashAttribute("success", "Updated " + person.getDisplayName() + ".");
                return "redirect:/people/" + id;
            } catch (AccessDeniedException e) {
                // a rejected change is a message on the form, not a 403 page
                bindingResult.reject("people.admin.denied", e.getMessage());
            }
        }
        prepareDetailModel(model, me, person);
        return "people/detail";
    }

    private void prepareDetailModel(Model model, UserAccount me, UserAccount person) {
        model.addAttribute("person", person);
        model.addAttribute("currentUser", me);
        model.addAttribute("keyCount", apiKeyService.countByOwner(List.of(person.getId()))
                .getOrDefault(person.getId(), 0L));
        model.addAttribute("canAdminister", me.administers(person) && !me.is(person));
        model.addAttribute("roles", me.isSuperAdmin() ? UserRole.values()
                : new UserRole[] {UserRole.GROUP_ADMIN, UserRole.MEMBER});
        model.addAttribute("groups", me.isSuperAdmin() ? userService.findAllGroups() : List.of());
    }
}
