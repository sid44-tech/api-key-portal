package io.github.apikeyportal.web;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import io.github.apikeyportal.appaccess.AppAccessGrant;
import io.github.apikeyportal.appaccess.AppAccessService;
import io.github.apikeyportal.appaccess.AppAccessToken;
import io.github.apikeyportal.appaccess.IssuedAppToken;
import io.github.apikeyportal.key.ApiKey;
import io.github.apikeyportal.notification.NotificationProperties;
import io.github.apikeyportal.user.PortalUser;
import io.github.apikeyportal.user.UserAccount;
import io.github.apikeyportal.user.UserAccountService;
import io.github.apikeyportal.user.UserGroup;
import io.github.apikeyportal.user.UserGroupRepository;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * The App access page: the super admin's server-wide switch, and the tokens
 * apps use to read keys. Members see their group's tokens read-only; group
 * admins and super admins create and delete them. Every rule is enforced again
 * in {@link AppAccessService} - this controller only decides what to show.
 */
@Controller
@RequestMapping("/app-access")
public class AppAccessController {

    private final AppAccessService appAccess;
    private final UserAccountService userService;
    private final UserGroupRepository groups;
    private final NotificationProperties notificationProperties;

    public AppAccessController(AppAccessService appAccess, UserAccountService userService,
            UserGroupRepository groups, NotificationProperties notificationProperties) {
        this.appAccess = appAccess;
        this.userService = userService;
        this.groups = groups;
        this.notificationProperties = notificationProperties;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal PortalUser principal, Model model) {
        UserAccount viewer = userService.getCurrent(principal);
        model.addAttribute("setting", appAccess.getSetting());
        model.addAttribute("tokens", appAccess.describeVisibleTo(viewer));
        model.addAttribute("isSuperAdmin", viewer.isSuperAdmin());
        model.addAttribute("canCreate", appAccess.isEnabled() && canCreateAny(viewer));
        return "app-access/list";
    }

    /** Super admins only - here, in the security configuration, and in the service. */
    @PostMapping("/switch")
    public String setEnabled(@AuthenticationPrincipal PortalUser principal,
            @RequestParam(value = "enabled", defaultValue = "false") boolean enabled,
            RedirectAttributes redirectAttributes) {
        appAccess.setEnabled(enabled, userService.getCurrent(principal));
        redirectAttributes.addFlashAttribute("success", enabled
                ? "Application access is on. Group admins can now create app tokens."
                : "Application access is off. Every app token is refused until it is switched back on; "
                        + "existing tokens are kept and can still be deleted.");
        return "redirect:/app-access";
    }

    @GetMapping("/new")
    public String newToken(@AuthenticationPrincipal PortalUser principal,
            @RequestParam(value = "groupId", required = false) Long groupId, Model model,
            RedirectAttributes redirectAttributes) {
        UserAccount viewer = userService.getCurrent(principal);
        if (!appAccess.isEnabled()) {
            redirectAttributes.addFlashAttribute("warning",
                    "Application access is switched off, so no tokens can be created.");
            return "redirect:/app-access";
        }
        AppTokenForm form = new AppTokenForm();
        form.setGroupId(groupId != null ? groupId : viewer.getGroupId());
        if (form.getGroupId() == null && viewer.isSuperAdmin()) {
            // a super admin outside every group starts on the first one
            groups.findAllByOrderByNameAsc().stream().findFirst().ifPresent(g -> form.setGroupId(g.getId()));
        }
        if (!AppAccessService.canManage(viewer, form.getGroupId())) {
            throw new AccessDeniedException("Only a group admin or super admin can create app tokens");
        }
        model.addAttribute("form", form);
        return showForm(viewer, form, model);
    }

    @PostMapping("/new")
    public String create(@AuthenticationPrincipal PortalUser principal,
            @Valid @ModelAttribute("form") AppTokenForm form, BindingResult bindingResult, Model model,
            HttpServletResponse response, RedirectAttributes redirectAttributes) {
        UserAccount viewer = userService.getCurrent(principal);
        if (bindingResult.hasErrors()) {
            return showForm(viewer, form, model);
        }
        IssuedAppToken issued;
        try {
            issued = appAccess.create(form.getName(), form.getGroupId(), form.getKeyIds(), form.getValidDays(),
                    viewer);
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("warning", e.getMessage());
            return "redirect:/app-access";
        } catch (IllegalArgumentException e) {
            bindingResult.reject("appToken.invalid", e.getMessage());
            return showForm(viewer, form, model);
        }
        // the one page the token ever appears on: never cached, never in history
        response.setHeader("Cache-Control", "no-store");
        AppAccessToken token = issued.token();
        Map<Long, ApiKey> byId = appAccess.selectableKeys(token.getGroupId()).stream()
                .collect(Collectors.toMap(ApiKey::getId, Function.identity()));
        model.addAttribute("token", token);
        model.addAttribute("plaintext", issued.plaintext());
        model.addAttribute("references", token.getGrants().stream()
                .map(grant -> new Reference(grant, byId.get(grant.getKeyId()))).toList());
        model.addAttribute("baseUrl", notificationProperties.getBaseUrl().replaceAll("/+$", ""));
        return "app-access/created";
    }

    /** Allowed with the feature switched off: taking access away is never blocked. */
    @PostMapping("/tokens/{id}/delete")
    public String delete(@PathVariable Long id, @AuthenticationPrincipal PortalUser principal,
            RedirectAttributes redirectAttributes) {
        AppAccessToken token = appAccess.delete(id, userService.getCurrent(principal));
        redirectAttributes.addFlashAttribute("success",
                "Deleted the app token " + token.getName() + ". Any app using it is refused from now on.");
        return "redirect:/app-access";
    }

    private String showForm(UserAccount viewer, AppTokenForm form, Model model) {
        List<UserGroup> manageable = viewer.isSuperAdmin() ? groups.findAllByOrderByNameAsc()
                : viewer.getGroup() == null ? List.of() : List.of(viewer.getGroup());
        model.addAttribute("groups", manageable);
        model.addAttribute("keys", appAccess.selectableKeys(form.getGroupId()));
        model.addAttribute("validityChoices", AppTokenForm.VALIDITY_CHOICES);
        return "app-access/form";
    }

    private static boolean canCreateAny(UserAccount viewer) {
        return viewer.isSuperAdmin() || AppAccessService.canManage(viewer, viewer.getGroupId());
    }

    /** One line of the "configure your app" table on the created page. */
    public record Reference(Long ref, String label) {
        Reference(AppAccessGrant grant, ApiKey key) {
            this(grant.getId(), key == null ? "Key " + grant.getKeyId() : key.getServiceName() + " / " + key.getName());
        }
    }
}
