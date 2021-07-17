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
import org.springframework.web.bind.annotation.RequestParam;

import com.sidbhat.apikeyportal.key.ApiKeyService;
import com.sidbhat.apikeyportal.key.ApiKeySort;

@Controller
@RequestMapping("/keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @GetMapping
    public String list(@RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "service", required = false) String service,
            @RequestParam(name = "sort", defaultValue = "SERVICE") ApiKeySort sort, Model model) {
        model.addAttribute("keys", apiKeyService.search(query, service, sort));
        model.addAttribute("summary", apiKeyService.getSummary());
        model.addAttribute("services", apiKeyService.findServiceNames());
        model.addAttribute("sorts", ApiKeySort.values());
        model.addAttribute("sort", sort);
        model.addAttribute("q", query);
        model.addAttribute("service", service);
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
                form.getExpiresOn(), form.getNotes());
        return "redirect:/keys";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        model.addAttribute("form", ApiKeyForm.from(apiKeyService.getById(id)));
        return "keys/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("form") ApiKeyForm form,
            BindingResult bindingResult) {
        form.setId(id);
        if (bindingResult.hasErrors()) {
            return "keys/form";
        }
        apiKeyService.update(id, form.getName(), form.getServiceName(), form.getKeyValue(), form.getCreatedOn(),
                form.getExpiresOn(), form.getNotes());
        return "redirect:/keys/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id) {
        apiKeyService.delete(id);
        return "redirect:/keys";
    }
}
