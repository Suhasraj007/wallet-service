package com.wallet.web;

import com.wallet.auth.Caller;
import com.wallet.dto.AccountResponse;
import com.wallet.service.AccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/accounts")
    public AccountResponse getOrCreate(@Caller String caller) {
        return accountService.getOrCreate(caller);
    }

    @GetMapping("/accounts/me")
    public AccountResponse me(@Caller String caller) {
        return accountService.me(caller);
    }
}
