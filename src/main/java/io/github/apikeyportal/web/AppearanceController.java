package io.github.apikeyportal.web;

import java.io.IOException;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import io.github.apikeyportal.theme.Logo;

import io.github.apikeyportal.theme.ThemeMode;
import io.github.apikeyportal.theme.ThemePalette;
import io.github.apikeyportal.theme.ThemeService;
import io.github.apikeyportal.user.PortalUser;
import io.github.apikeyportal.user.UserAccountService;

/**
 * The portal's colours, name and logo. Super admin only - the route is
 * restricted in {@code SecurityConfig}, since this changes what everybody sees.
 */
@Controller
@RequestMapping("/appearance")
public class AppearanceController {

    /** The fields on the page, in the order they are shown, with what each one colours. */
    static final List<ColorField> FIELDS = List.of(
            new ColorField("accent", "Accent", "Links, primary buttons and focus rings"),
            new ColorField("canvas", "Page background", "Behind everything"),
            new ColorField("surface", "Card background", "Cards, tables and panels"),
            new ColorField("ink", "Text", "Body text; the softer greys are mixed from this"),
            new ColorField("success", "Success", "\"Active\" and \"created\""),
            new ColorField("warning", "Warning", "\"Expiring soon\""),
            new ColorField("danger", "Danger", "\"Expired\" and delete"));

    private final ThemeService themeService;
    private final UserAccountService userService;

    public AppearanceController(ThemeService themeService, UserAccountService userService) {
        this.themeService = themeService;
        this.userService = userService;
    }

    @GetMapping
    public String settings(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", AppearanceForm.from(themeService.portalName(),
                    themeService.palette(ThemeMode.LIGHT), themeService.palette(ThemeMode.DARK)));
        }
        prepareModel(model);
        return "appearance/settings";
    }

    @PostMapping
    public String save(@AuthenticationPrincipal PortalUser principal,
            @Valid @ModelAttribute("form") AppearanceForm form, BindingResult bindingResult, Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            prepareModel(model);
            return "appearance/settings";
        }
        themeService.save(form.palette(ThemeMode.LIGHT), form.palette(ThemeMode.DARK), form.getPortalName(),
                userService.getCurrent(principal));
        redirectAttributes.addFlashAttribute("success", "Saved. Everyone sees the new look on their next page.");
        return "redirect:/appearance";
    }

    /** The logo is its own form: a file upload with its own checks, separate from the colours. */
    @PostMapping("/logo")
    public String uploadLogo(@AuthenticationPrincipal PortalUser principal,
            @RequestParam("logo") MultipartFile file, RedirectAttributes redirectAttributes) {
        try {
            themeService.saveLogo(file.getBytes(), userService.getCurrent(principal));
            redirectAttributes.addFlashAttribute("success", "Logo uploaded. It replaces the key mark everywhere.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", "The upload could not be read. Try again.");
        }
        return "redirect:/appearance";
    }

    @PostMapping("/logo/remove")
    public String removeLogo(@AuthenticationPrincipal PortalUser principal, RedirectAttributes redirectAttributes) {
        themeService.removeLogo(userService.getCurrent(principal));
        redirectAttributes.addFlashAttribute("success", "Logo removed; the key mark is back.");
        return "redirect:/appearance";
    }

    /** The container refuses oversized uploads before the controller sees them; say so in words. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("error",
                "That file is too large. The logo must be " + (Logo.MAX_BYTES / 1024) + " KB or smaller.");
        return "redirect:/appearance";
    }

    @PostMapping("/reset")
    public String reset(@AuthenticationPrincipal PortalUser principal, RedirectAttributes redirectAttributes) {
        themeService.reset(userService.getCurrent(principal));
        redirectAttributes.addFlashAttribute("success", "Back to the built-in colours.");
        return "redirect:/appearance";
    }

    private void prepareModel(Model model) {
        model.addAttribute("modes", ThemeMode.values());
        model.addAttribute("fields", FIELDS);
        model.addAttribute("customised", themeService.isCustomised());
        model.addAttribute("logoAccepted", Logo.ACCEPTED);
        model.addAttribute("logoMaxKb", Logo.MAX_BYTES / 1024);
        model.addAttribute("defaultLight", ThemePalette.DEFAULT_LIGHT);
        model.addAttribute("defaultDark", ThemePalette.DEFAULT_DARK);
    }

    /** @param name the property on {@link AppearanceForm.ColorSet} */
    public record ColorField(String name, String label, String help) {
    }
}
