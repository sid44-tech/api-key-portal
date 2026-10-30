package com.sidbhat.apikeyportal.integration;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ServiceIntegrationNotFoundException extends RuntimeException {

    public ServiceIntegrationNotFoundException(Long id) {
        super("No service integration with id " + id);
    }
}
