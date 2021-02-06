package com.sidbhat.apikeyportal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiKeyPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiKeyPortalApplication.class, args);
    }

}
