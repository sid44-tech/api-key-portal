package com.sidbhat.apikeyportal.user;

public class GroupNameAlreadyUsedException extends RuntimeException {

    public GroupNameAlreadyUsedException(String name) {
        super("A group called \"" + name + "\" already exists. Pick a different name.");
    }
}
