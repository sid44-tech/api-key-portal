package com.sidbhat.apikeyportal.theme;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sidbhat.apikeyportal.activity.ActivityAction;
import com.sidbhat.apikeyportal.activity.ActivityService;
import com.sidbhat.apikeyportal.user.UserAccount;

/**
 * The portal's colours and branding: what is stored, what is in effect, and
 * the stylesheet that carries the colours to the browser.
 * <p>
 * The stylesheet route exists because of the Content-Security-Policy. Inline
 * styles are forbidden everywhere, so a chosen colour cannot be written into a
 * {@code style} attribute or a {@code <style>} block; a same-origin stylesheet
 * is the one thing {@code style-src 'self'} permits, so the chosen palette is
 * served as one. It is empty while nothing is customised, which keeps
 * {@code app.css} the single source of truth for the built-in look.
 */
@Service
@Transactional(readOnly = true)
public class ThemeService {

    private static final Logger log = LoggerFactory.getLogger(ThemeService.class);

    private static final String TARGET_NAME = "Appearance";

    /** What the portal is called until a super admin renames it. */
    public static final String DEFAULT_NAME = "API Key Portal";

    private final ThemeSettingsRepository repository;
    private final ActivityService activity;

    public ThemeService(ThemeSettingsRepository repository, ActivityService activity) {
        this.repository = repository;
        this.activity = activity;
    }

    public Optional<ThemeSettings> find() {
        return repository.findFirstByOrderByIdAsc();
    }

    /** True once a super admin has saved anything of their own: colours, a name or a logo. */
    public boolean isCustomised() {
        return find().isPresent();
    }

    /** The name shown in the header, the page titles and on the sign-in page. */
    public String portalName() {
        return find().map(ThemeSettings::getPortalName).filter(name -> !name.isBlank()).orElse(DEFAULT_NAME);
    }

    public Optional<Logo> logo() {
        return find().map(ThemeSettings::getLogo);
    }

    public boolean hasLogo() {
        return logo().isPresent();
    }

    /** The palette in effect for {@code mode}: the stored one, or app.css's defaults. */
    public ThemePalette palette(ThemeMode mode) {
        return find().map(settings -> settings.palette(mode)).orElseGet(() -> ThemePalette.defaultFor(mode));
    }

    /**
     * The stylesheet the layout links: both modes' overrides, or nothing at all
     * while the built-in look is in use.
     */
    public String stylesheet() {
        return find().map(settings -> "/* Colours chosen under Appearance. */\n"
                        + settings.palette(ThemeMode.LIGHT).toCss(ThemeMode.LIGHT)
                        + settings.palette(ThemeMode.DARK).toCss(ThemeMode.DARK))
                .orElse("/* Built-in colours; nothing customised under Appearance. */\n");
    }

    /**
     * Changes with every save, so the layout can put it in the stylesheet URL and
     * a browser holding yesterday's colours fetches today's.
     */
    public String version() {
        return find().map(settings -> settings.getUpdatedAt() == null ? "1"
                : String.valueOf(settings.getUpdatedAt().toEpochMilli())).orElse("0");
    }

    /**
     * @param portalName the name to show; blank or the default means "the built-in name"
     */
    @Transactional
    public ThemeSettings save(ThemePalette light, ThemePalette dark, String portalName, UserAccount admin) {
        ThemeSettings settings = find().orElseGet(() -> new ThemeSettings(light, dark));
        settings.apply(ThemeMode.LIGHT, light);
        settings.apply(ThemeMode.DARK, dark);
        String name = portalName == null ? "" : portalName.trim();
        settings.setPortalName(name.isEmpty() || name.equals(DEFAULT_NAME) ? null : name);
        ThemeSettings saved = repository.save(settings);
        activity.record(ActivityAction.APPEARANCE_CHANGED, admin, saved.getId(), TARGET_NAME,
                "Colours and name saved; light accent " + light.accent() + ", dark accent " + dark.accent()
                        + ", name \"" + portalName() + "\"");
        log.info("{} changed the portal's appearance: {}", admin.getEmail(), saved);
        return saved;
    }

    /**
     * Stores an uploaded logo, keeping whatever colours and name are already
     * set - or the defaults, if the logo is the first thing customised.
     *
     * @throws IllegalArgumentException if the file is not an acceptable image - see {@link Logo#validated}
     */
    @Transactional
    public ThemeSettings saveLogo(byte[] upload, UserAccount admin) {
        Logo logo = Logo.validated(upload);
        ThemeSettings settings = find()
                .orElseGet(() -> new ThemeSettings(ThemePalette.DEFAULT_LIGHT, ThemePalette.DEFAULT_DARK));
        settings.setLogo(logo);
        ThemeSettings saved = repository.save(settings);
        activity.record(ActivityAction.APPEARANCE_CHANGED, admin, saved.getId(), TARGET_NAME,
                "Logo uploaded (" + logo.contentType() + ", " + upload.length + " bytes)");
        log.info("{} uploaded a logo: {}", admin.getEmail(), logo);
        return saved;
    }

    /** Back to the key mark. Colours and name stay as they are. */
    @Transactional
    public void removeLogo(UserAccount admin) {
        find().filter(settings -> settings.getLogo() != null).ifPresent(settings -> {
            settings.setLogo(null);
            repository.save(settings);
            activity.record(ActivityAction.APPEARANCE_CHANGED, admin, settings.getId(), TARGET_NAME,
                    "Logo removed");
            log.info("{} removed the logo", admin.getEmail());
        });
    }

    /** Back to the built-in look - colours, name and logo. Deletes the row rather than writing the defaults into it. */
    @Transactional
    public void reset(UserAccount admin) {
        find().ifPresent(settings -> {
            repository.delete(settings);
            activity.record(ActivityAction.APPEARANCE_CHANGED, admin, settings.getId(), TARGET_NAME,
                    "Reset to the built-in colours, name and logo");
            log.info("{} reset the portal's colours to the defaults", admin.getEmail());
        });
    }
}
