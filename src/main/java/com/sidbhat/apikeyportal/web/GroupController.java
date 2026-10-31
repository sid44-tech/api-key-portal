package com.sidbhat.apikeyportal.web;

import jakarta.validation.Valid;

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

import com.sidbhat.apikeyportal.group.GroupNotEmptyException;
import com.sidbhat.apikeyportal.group.GroupService;
import com.sidbhat.apikeyportal.user.GroupNameAlreadyUsedException;
import com.sidbhat.apikeyportal.user.PortalUser;
import com.sidbhat.apikeyportal.user.UserAccountService;

/**
 * Groups, for a super admin. Everyone else never sees this page - the security
 * configuration restricts {@code /groups} to {@code ROLE_SUPER_ADMIN}.
 */
@Controller
@RequestMapping("/groups")
public class GroupController {

    private final GroupService groupService;
    private final UserAccountService userService;

    public GroupController(GroupService groupService, UserAccountService userService) {
        this.groupService = groupService;
        this.userService = userService;
    }

    @GetMapping
    public String list(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new GroupForm());
        }
        model.addAttribute("groups", groupService.findAllWithUsage());
        return "groups/list";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal PortalUser principal,
            @Valid @ModelAttribute("form") GroupForm form, BindingResult bindingResult, Model model,
            RedirectAttributes redirectAttributes) {
        if (!bindingResult.hasErrors()) {
            try {
                redirectAttributes.addFlashAttribute("success", "Added the group "
                        + groupService.create(form.getName(), userService.getCurrent(principal)).getName() + ".");
                return "redirect:/groups";
            } catch (GroupNameAlreadyUsedException e) {
                bindingResult.rejectValue("name", "group.name.taken", e.getMessage());
            }
        }
        model.addAttribute("groups", groupService.findAllWithUsage());
        return "groups/list";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            groupService.delete(id);
            redirectAttributes.addFlashAttribute("success", "Group deleted.");
        } catch (GroupNotEmptyException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/groups";
    }
}
