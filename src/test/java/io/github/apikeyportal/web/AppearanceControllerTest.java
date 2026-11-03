package io.github.apikeyportal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import io.github.apikeyportal.security.SecurityConfig;
import io.github.apikeyportal.theme.Logo;
import io.github.apikeyportal.theme.ThemeMode;
import io.github.apikeyportal.theme.ThemePalette;
import io.github.apikeyportal.theme.ThemeService;
import io.github.apikeyportal.user.PortalUser;
import io.github.apikeyportal.user.TestUsers;
import io.github.apikeyportal.user.UserAccountService;
import io.github.apikeyportal.user.WithPortalUser;

@WebMvcTest(controllers = {AppearanceController.class, ThemeStylesheetController.class, ThemeLogoController.class})
@Import(SecurityConfig.class)
class AppearanceControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ThemeService themeService;

    @MockitoBean
    private UserAccountService userService;

    @BeforeEach
    void stubs() {
        when(userService.getCurrent(any()))
                .thenAnswer(invocation -> TestUsers.accountFor((PortalUser) invocation.getArgument(0)));
        when(themeService.palette(ThemeMode.LIGHT)).thenReturn(ThemePalette.DEFAULT_LIGHT);
        when(themeService.palette(ThemeMode.DARK)).thenReturn(ThemePalette.DEFAULT_DARK);
        when(themeService.version()).thenReturn("0");
        when(themeService.stylesheet()).thenReturn("/* none */\n");
        when(themeService.portalName()).thenReturn(ThemeService.DEFAULT_NAME);
        when(themeService.logo()).thenReturn(Optional.empty());
    }

    /** A complete, valid submission, built from the defaults with one accent changed. */
    private static MockHttpServletRequestBuilder validSave(String lightAccent) {
        return validSave("Keys at Contoso", lightAccent);
    }

    private static MockHttpServletRequestBuilder validSave(String name, String lightAccent) {
        MockHttpServletRequestBuilder request = post("/appearance").with(csrf()).param("portalName", name);
        for (ThemeMode mode : ThemeMode.values()) {
            ThemePalette p = ThemePalette.defaultFor(mode);
            String prefix = mode.getPrefix() + ".";
            request.param(prefix + "accent", mode == ThemeMode.LIGHT ? lightAccent : p.accent())
                    .param(prefix + "canvas", p.canvas()).param(prefix + "surface", p.surface())
                    .param(prefix + "ink", p.ink()).param(prefix + "success", p.success())
                    .param(prefix + "warning", p.warning()).param(prefix + "danger", p.danger());
        }
        return request;
    }

    @Test
    void redirectsAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/appearance")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithPortalUser("sam")
    void aMemberCannotReachThePage() throws Exception {
        mvc.perform(get("/appearance")).andExpect(status().isForbidden());
    }

    @Test
    @WithPortalUser("alex")
    void aGroupAdminCannotChangeTheColoursEither() throws Exception {
        // what everybody sees is the super admin's call alone
        mvc.perform(get("/appearance")).andExpect(status().isForbidden());
        mvc.perform(validSave("#ff0000")).andExpect(status().isForbidden());
        verify(themeService, never()).save(any(), any(), any(), any());
    }

    @Test
    @WithPortalUser("superadmin")
    void theSuperAdminSeesBothModesWithTheCurrentColoursFilledIn() throws Exception {
        mvc.perform(get("/appearance"))
                .andExpect(status().isOk())
                .andExpect(view().name("appearance/settings"))
                .andExpect(content().string(Matchers.containsString("Light theme")))
                .andExpect(content().string(Matchers.containsString("Dark theme")))
                // the hex fields hold the colours in effect; the picker beside each is a mirror
                .andExpect(content().string(Matchers.containsString("value=\"#0a5fd6\"")))
                .andExpect(content().string(Matchers.containsString("value=\"#3b8cf6\"")))
                .andExpect(content().string(Matchers.containsString("type=\"color\"")))
                .andExpect(content().string(Matchers.containsString("/js/appearance.js")));
    }

    @Test
    @WithPortalUser("superadmin")
    void savingPassesNormalisedPalettesToTheService() throws Exception {
        mvc.perform(validSave("C62D2D")).andExpect(redirectedUrl("/appearance"));

        ArgumentCaptor<ThemePalette> light = ArgumentCaptor.forClass(ThemePalette.class);
        ArgumentCaptor<ThemePalette> dark = ArgumentCaptor.forClass(ThemePalette.class);
        verify(themeService).save(light.capture(), dark.capture(), org.mockito.ArgumentMatchers.eq("Keys at Contoso"), any());
        // typed without a hash and in capitals; stored tidy
        assertThat(light.getValue().accent()).isEqualTo("#c62d2d");
        assertThat(dark.getValue()).isEqualTo(ThemePalette.DEFAULT_DARK);
    }

    @Test
    @WithPortalUser("superadmin")
    void aValueThatIsNotAColourIsRefusedOnTheFormAndNothingIsSaved() throws Exception {
        mvc.perform(validSave("not-a-colour"))
                .andExpect(status().isOk())
                .andExpect(view().name("appearance/settings"))
                .andExpect(model().attributeHasFieldErrors("form", "light.accent"))
                .andExpect(content().string(Matchers.containsString("six-digit hex colour")));

        verify(themeService, never()).save(any(), any(), any(), any());
    }

    @Test
    @WithPortalUser("superadmin")
    void aBlankNameIsRefused() throws Exception {
        mvc.perform(validSave("  ", "#0a5fd6"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "portalName"));

        verify(themeService, never()).save(any(), any(), any(), any());
    }

    @Test
    @WithPortalUser("superadmin")
    void uploadingALogoHandsTheBytesToTheService() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

        mvc.perform(multipart("/appearance/logo").file(new MockMultipartFile("logo", "mark.png", "image/png", png))
                        .with(csrf()))
                .andExpect(redirectedUrl("/appearance"));

        verify(themeService).saveLogo(org.mockito.ArgumentMatchers.eq(png), any());
    }

    @Test
    @WithPortalUser("superadmin")
    void aRefusedLogoComesBackAsAMessageNotAnError() throws Exception {
        when(themeService.saveLogo(any(), any())).thenThrow(new IllegalArgumentException("That file is not an image"));

        mvc.perform(multipart("/appearance/logo").file(new MockMultipartFile("logo", "x.txt", "text/plain", "hi".getBytes()))
                        .with(csrf()))
                .andExpect(redirectedUrl("/appearance"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                        .attribute("error", "That file is not an image"));
    }

    @Test
    @WithPortalUser("sam")
    void aMemberCannotUploadALogo() throws Exception {
        mvc.perform(multipart("/appearance/logo").file(new MockMultipartFile("logo", "x.png", "image/png", new byte[] {1}))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        verify(themeService, never()).saveLogo(any(), any());
    }

    @Test
    @WithPortalUser("superadmin")
    void removingTheLogoGoesThroughTheService() throws Exception {
        mvc.perform(post("/appearance/logo/remove").with(csrf())).andExpect(redirectedUrl("/appearance"));
        verify(themeService).removeLogo(any());
    }

    @Test
    void theLogoIsServedPubliclyWithAPolicyThatNeutralisesScriptInAnSvg() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes();
        when(themeService.logo()).thenReturn(Optional.of(new Logo(svg, "image/svg+xml")));
        when(themeService.version()).thenReturn("7");

        mvc.perform(get("/theme/logo"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/svg+xml"))
                .andExpect(header().string("Content-Security-Policy", ThemeLogoController.LOGO_CSP))
                .andExpect(header().string("ETag", "\"7\""))
                .andExpect(content().bytes(svg));
    }

    @Test
    void thereIsNoLogoUntilOneIsUploaded() throws Exception {
        mvc.perform(get("/theme/logo")).andExpect(status().isNotFound());
    }

    @Test
    @WithPortalUser("superadmin")
    void resettingGoesThroughTheService() throws Exception {
        mvc.perform(post("/appearance/reset").with(csrf())).andExpect(redirectedUrl("/appearance"));

        verify(themeService).reset(any());
    }

    @Test
    void theStylesheetIsPublicBecauseTheSignInPageWearsTheColoursToo() throws Exception {
        when(themeService.stylesheet()).thenReturn(":root {\n    --kp-accent: #c62d2d;\n}\n");
        when(themeService.version()).thenReturn("42");

        mvc.perform(get("/theme.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", Matchers.startsWith("text/css")))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("ETag", "\"42\""))
                .andExpect(content().string(Matchers.containsString("--kp-accent: #c62d2d;")));
    }
}
