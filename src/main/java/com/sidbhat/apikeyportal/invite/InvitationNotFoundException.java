package com.sidbhat.apikeyportal.invite;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class InvitationNotFoundException extends RuntimeException {

    public InvitationNotFoundException(Long id) {
        super("No invitation with id " + id);
    }
}
