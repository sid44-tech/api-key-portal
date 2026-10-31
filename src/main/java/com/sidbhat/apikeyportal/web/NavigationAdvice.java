package com.sidbhat.apikeyportal.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Tells the layout which navigation item to mark as current.
 * <p>
 * Thymeleaf 3.1 removed direct access to the request, so the section is worked
 * out here instead of in the template. It is the first path segment, which is
 * also how the navigation is grouped: {@code /keys/3/edit} is still "keys".
 */
@ControllerAdvice
public class NavigationAdvice {

    @ModelAttribute("activeNav")
    public String activeNav(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        int next = path.indexOf('/', 1);
        return next < 0 ? path.substring(1) : path.substring(1, next);
    }
}
