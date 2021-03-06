package com.sidbhat.apikeyportal.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
                .authorizeRequests(authorize -> authorize
                        .antMatchers("/webjars/**", "/css/**", "/js/**", "/favicon.ico").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.defaultSuccessUrl("/keys"))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"));
    }

    @Bean
    public UserDetailsService userDetailsService(SecurityProperties properties) {
        return new InMemoryUserDetailsManager(User.withUsername(properties.getUsername())
                .password(properties.getPasswordHash())
                .roles("USER")
                .build());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
