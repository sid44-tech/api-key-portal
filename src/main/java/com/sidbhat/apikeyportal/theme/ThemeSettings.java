package com.sidbhat.apikeyportal.theme;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The colours a super admin has chosen for the portal, one row for the whole
 * installation. No row means the built-in look; "reset to defaults" deletes it.
 */
@Entity
@Table(name = "theme_settings")
@EntityListeners(AuditingEntityListener.class)
public class ThemeSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "light_accent", nullable = false, length = 7)
    private String lightAccent;
    @Column(name = "light_canvas", nullable = false, length = 7)
    private String lightCanvas;
    @Column(name = "light_surface", nullable = false, length = 7)
    private String lightSurface;
    @Column(name = "light_ink", nullable = false, length = 7)
    private String lightInk;
    @Column(name = "light_success", nullable = false, length = 7)
    private String lightSuccess;
    @Column(name = "light_warning", nullable = false, length = 7)
    private String lightWarning;
    @Column(name = "light_danger", nullable = false, length = 7)
    private String lightDanger;

    @Column(name = "dark_accent", nullable = false, length = 7)
    private String darkAccent;
    @Column(name = "dark_canvas", nullable = false, length = 7)
    private String darkCanvas;
    @Column(name = "dark_surface", nullable = false, length = 7)
    private String darkSurface;
    @Column(name = "dark_ink", nullable = false, length = 7)
    private String darkInk;
    @Column(name = "dark_success", nullable = false, length = 7)
    private String darkSuccess;
    @Column(name = "dark_warning", nullable = false, length = 7)
    private String darkWarning;
    @Column(name = "dark_danger", nullable = false, length = 7)
    private String darkDanger;

    /** The name in the header, the page titles and the sign-in page; null means the built-in one. */
    @Column(name = "portal_name", length = 60)
    private String portalName;

    /** An uploaded mark in place of the key icon; null means the built-in one. */
    @Column(name = "logo")
    private byte[] logo;

    @Column(name = "logo_content_type", length = 100)
    private String logoContentType;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ThemeSettings() {
        // for JPA
    }

    public ThemeSettings(ThemePalette light, ThemePalette dark) {
        apply(ThemeMode.LIGHT, light);
        apply(ThemeMode.DARK, dark);
    }

    public Long getId() {
        return id;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getPortalName() {
        return portalName;
    }

    public void setPortalName(String portalName) {
        this.portalName = portalName;
    }

    public Logo getLogo() {
        return logo == null ? null : new Logo(logo, logoContentType);
    }

    public void setLogo(Logo newLogo) {
        this.logo = newLogo == null ? null : newLogo.bytes();
        this.logoContentType = newLogo == null ? null : newLogo.contentType();
    }

    public ThemePalette palette(ThemeMode mode) {
        return mode == ThemeMode.DARK
                ? new ThemePalette(darkAccent, darkCanvas, darkSurface, darkInk, darkSuccess, darkWarning, darkDanger)
                : new ThemePalette(lightAccent, lightCanvas, lightSurface, lightInk, lightSuccess, lightWarning,
                        lightDanger);
    }

    public void apply(ThemeMode mode, ThemePalette palette) {
        if (mode == ThemeMode.DARK) {
            darkAccent = palette.accent();
            darkCanvas = palette.canvas();
            darkSurface = palette.surface();
            darkInk = palette.ink();
            darkSuccess = palette.success();
            darkWarning = palette.warning();
            darkDanger = palette.danger();
        } else {
            lightAccent = palette.accent();
            lightCanvas = palette.canvas();
            lightSurface = palette.surface();
            lightInk = palette.ink();
            lightSuccess = palette.success();
            lightWarning = palette.warning();
            lightDanger = palette.danger();
        }
    }

    @Override
    public String toString() {
        return "ThemeSettings{id=" + id + ", lightAccent='" + lightAccent + "', darkAccent='" + darkAccent
                + "', portalName='" + portalName + "', logo=" + (logo == null ? "none" : logoContentType) + "}";
    }
}
