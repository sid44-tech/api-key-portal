package com.sidbhat.apikeyportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.sidbhat.apikeyportal.activity.ActivityAction;
import com.sidbhat.apikeyportal.activity.ActivityEventRepository;
import com.sidbhat.apikeyportal.theme.ThemeMode;
import com.sidbhat.apikeyportal.theme.ThemePalette;
import com.sidbhat.apikeyportal.theme.ThemeSettingsRepository;

/**
 * Choosing colours end to end: the save, the stylesheet every page links, the
 * page that links it, and the way back to the defaults.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AppearanceIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ThemeSettingsRepository settings;

    @Autowired
    private ActivityEventRepository events;

    @AfterEach
    void cleanUp() {
        settings.deleteAll();
        events.deleteAll();
    }

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    private static MockHttpServletRequestBuilder save(String lightAccent, String darkCanvas) {
        return save("API Key Portal", lightAccent, darkCanvas);
    }

    private static MockHttpServletRequestBuilder save(String name, String lightAccent, String darkCanvas) {
        MockHttpServletRequestBuilder request = post("/appearance").with(csrf()).param("portalName", name);
        for (ThemeMode mode : ThemeMode.values()) {
            ThemePalette p = ThemePalette.defaultFor(mode);
            String prefix = mode.getPrefix() + ".";
            request.param(prefix + "accent", mode == ThemeMode.LIGHT ? lightAccent : p.accent())
                    .param(prefix + "canvas", mode == ThemeMode.DARK ? darkCanvas : p.canvas())
                    .param(prefix + "surface", p.surface()).param(prefix + "ink", p.ink())
                    .param(prefix + "success", p.success()).param(prefix + "warning", p.warning())
                    .param(prefix + "danger", p.danger());
        }
        return request;
    }

    @Test
    void theBuiltInLookShipsAnEmptyStylesheet() throws Exception {
        mvc.perform(get("/theme.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("--kp-accent"))));
    }

    @Test
    @WithUserDetails("admin@example.com")
    void savedColoursReachEveryPageThroughTheVersionedStylesheet() throws Exception {
        mvc.perform(save("#C62D2D", "#101418")).andExpect(redirectedUrl("/appearance"));

        // the stylesheet carries both modes, derived tokens included
        String css = mvc.perform(get("/theme.css")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(css).contains(":root {").contains("--kp-accent: #c62d2d;")
                .contains("--kp-accent-soft: rgba(198, 45, 45, 0.10);")
                .contains(":root[data-bs-theme=\"dark\"] {").contains("--kp-canvas: #101418;");

        // and the pages link it with a version that is no longer the default's
        String page = mvc.perform(get("/keys")).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("/theme.css?v=").doesNotContain("/theme.css?v=0\"");

        // the form shows what was saved, tidied
        mvc.perform(get("/appearance"))
                .andExpect(content().string(containsString("value=\"#c62d2d\"")))
                .andExpect(content().string(containsString("Reset to defaults")));

        assertThat(events.findAllByOrderByOccurredAtDesc(PageRequest.of(0, 5)).getContent())
                .anySatisfy(event -> assertThat(event.getAction()).isEqualTo(ActivityAction.APPEARANCE_CHANGED));
    }

    @Test
    @WithUserDetails("admin@example.com")
    void resettingDeletesTheCustomColoursRatherThanStoringTheDefaults() throws Exception {
        mvc.perform(save("#C62D2D", "#101418"));
        assertThat(settings.count()).isEqualTo(1);

        mvc.perform(post("/appearance/reset").with(csrf())).andExpect(redirectedUrl("/appearance"));

        assertThat(settings.count()).isZero();
        mvc.perform(get("/theme.css")).andExpect(content().string(not(containsString("--kp-accent"))));
        mvc.perform(get("/appearance")).andExpect(content().string(not(containsString("Reset to defaults"))));
    }

    @Test
    @WithUserDetails("admin@example.com")
    void theNameShowsInTheHeaderTheTitleAndOnTheSignInPage() throws Exception {
        mvc.perform(save("Keys at Contoso", "#0a5fd6", "#000000")).andExpect(redirectedUrl("/appearance"));

        String page = mvc.perform(get("/keys")).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("<title>Keys · Keys at Contoso</title>")
                .contains(">Keys at Contoso</span>")
                .doesNotContain(">API Key Portal</span>");

        // the sign-in page is rendered for whoever arrives, name included
        String login = mvc.perform(get("/login")).andReturn().getResponse().getContentAsString();
        assertThat(login).contains("Sign in to Keys at Contoso");

        // saving the default name again means "the built-in one", stored as such
        mvc.perform(save("API Key Portal", "#0a5fd6", "#000000"));
        assertThat(settings.findFirstByOrderByIdAsc().orElseThrow().getPortalName()).isNull();
    }

    @Test
    @WithUserDetails("admin@example.com")
    void anUploadedLogoReplacesTheKeyMarkEverywhereAndCanBeRemovedAgain() throws Exception {
        // the default look: the key mark, no logo
        assertThat(mvc.perform(get("/keys")).andReturn().getResponse().getContentAsString())
                .contains("kp-brand-mark").doesNotContain("kp-brand-logo");
        mvc.perform(get("/theme/logo")).andExpect(status().isNotFound());

        mvc.perform(multipart("/appearance/logo").file(new MockMultipartFile("logo", "mark.png", "image/png", PNG))
                        .with(csrf()))
                .andExpect(redirectedUrl("/appearance"));

        mvc.perform(get("/theme/logo"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(PNG));
        String page = mvc.perform(get("/keys")).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("class=\"kp-brand-logo\"").contains("/theme/logo?v=")
                .contains("rel=\"icon\"");
        assertThat(mvc.perform(get("/login")).andReturn().getResponse().getContentAsString())
                .contains("kp-brand-logo");

        mvc.perform(post("/appearance/logo/remove").with(csrf())).andExpect(redirectedUrl("/appearance"));
        mvc.perform(get("/theme/logo")).andExpect(status().isNotFound());
        assertThat(mvc.perform(get("/keys")).andReturn().getResponse().getContentAsString())
                .contains("kp-brand-mark").doesNotContain("kp-brand-logo");
    }

    @Test
    @WithUserDetails("admin@example.com")
    void aFileThatIsNotAnImageIsRefusedWithAMessageAndNothingIsStored() throws Exception {
        mvc.perform(multipart("/appearance/logo")
                        .file(new MockMultipartFile("logo", "mark.png", "image/png", "<html>not a png</html>".getBytes()))
                        .with(csrf()))
                .andExpect(redirectedUrl("/appearance"))
                .andExpect(flash().attributeExists("error"));

        assertThat(settings.count()).isZero();
        mvc.perform(get("/theme/logo")).andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails("admin@example.com")
    void resetReturnsTheNameAndLogoToTheDefaultsToo() throws Exception {
        mvc.perform(save("Keys at Contoso", "#c62d2d", "#101418"));
        mvc.perform(multipart("/appearance/logo").file(new MockMultipartFile("logo", "mark.png", "image/png", PNG))
                .with(csrf()));

        mvc.perform(post("/appearance/reset").with(csrf())).andExpect(redirectedUrl("/appearance"));

        String page = mvc.perform(get("/keys")).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("<title>Keys · API Key Portal</title>").contains("kp-brand-mark")
                .doesNotContain("kp-brand-logo");
        mvc.perform(get("/theme/logo")).andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails("admin@example.com")
    void theNavigationOffersThePageToTheSuperAdmin() throws Exception {
        mvc.perform(get("/keys")).andExpect(content().string(containsString("href=\"/appearance\"")));
    }
}
