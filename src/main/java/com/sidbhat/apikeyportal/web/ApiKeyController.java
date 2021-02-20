package com.sidbhat.apikeyportal.web;

import javax.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.sidbhat.apikeyportal.key.ApiKeyService;

@Controller
@RequestMapping("/keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("keys", apiKeyService.findAll());
        return "keys/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("key", apiKeyService.getById(id));
        return "keys/detail";
    }

    @GetMapping("/new")
    public String newKey(Model model) {
        model.addAttribute("form", new ApiKeyForm());
        return "keys/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") ApiKeyForm form, BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            return "keys/form";
        }
        apiKeyService.create(form.getName(), form.getServiceName(), form.getKeyValue(), form.getCreatedOn(),
                form.getExpiresOn());
        return "redirect:/keys";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id) {
        apiKeyService.delete(id);
        return "redirect:/keys";
    }
}
