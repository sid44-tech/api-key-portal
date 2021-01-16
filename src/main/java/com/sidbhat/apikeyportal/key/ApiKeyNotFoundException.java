package com.sidbhat.apikeyportal.key;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ApiKeyNotFoundException extends RuntimeException {

    public ApiKeyNotFoundException(Long id) {
        super("No API key with id " + id);
    }
}
