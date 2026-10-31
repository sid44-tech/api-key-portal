package com.sidbhat.apikeyportal.user;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class UserAccountNotFoundException extends RuntimeException {

    public UserAccountNotFoundException(Long id) {
        super("No user account with id " + id);
    }
}
