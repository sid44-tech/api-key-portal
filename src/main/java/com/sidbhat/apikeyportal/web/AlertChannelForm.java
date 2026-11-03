package com.sidbhat.apikeyportal.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.sidbhat.apikeyportal.alerts.AlertChannel;

/** Backing object for a chat channel page (Teams, Slack). */
public class AlertChannelForm {

    @NotBlank(message = "give the channel a name, so the integrations page can say where alerts go")
    @Size(max = 60)
    private String name;

    /** Blank on an edit keeps the stored URL; it is never rendered back out. */
    @Size(max = 4000)
    private String webhookUrl;

    private boolean enabled = true;
    private boolean notifyExpiries = true;
    private boolean notifyRotationFailures = true;
    private boolean notifyLockouts;

    /** True once a URL is stored, so the form can say "leave blank to keep it". */
    private boolean webhookStored;

    public static AlertChannelForm from(AlertChannel channel) {
        AlertChannelForm form = new AlertChannelForm();
        form.name = channel.getName();
        form.enabled = channel.isEnabled();
        form.notifyExpiries = channel.isNotifyExpiries();
        form.notifyRotationFailures = channel.isNotifyRotationFailures();
        form.notifyLockouts = channel.isNotifyLockouts();
        form.webhookStored = true;
        return form;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isNotifyExpiries() {
        return notifyExpiries;
    }

    public void setNotifyExpiries(boolean notifyExpiries) {
        this.notifyExpiries = notifyExpiries;
    }

    public boolean isNotifyRotationFailures() {
        return notifyRotationFailures;
    }

    public void setNotifyRotationFailures(boolean notifyRotationFailures) {
        this.notifyRotationFailures = notifyRotationFailures;
    }

    public boolean isNotifyLockouts() {
        return notifyLockouts;
    }

    public void setNotifyLockouts(boolean notifyLockouts) {
        this.notifyLockouts = notifyLockouts;
    }

    public boolean isWebhookStored() {
        return webhookStored;
    }

    public void setWebhookStored(boolean webhookStored) {
        this.webhookStored = webhookStored;
    }
}
