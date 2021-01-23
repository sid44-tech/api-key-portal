package com.sidbhat.apikeyportal.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
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
}
