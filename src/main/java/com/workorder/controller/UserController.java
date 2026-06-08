package com.workorder.controller;

import com.workorder.common.Result;
import com.workorder.common.dto.RegisterReq;
import com.workorder.entity.User;
import com.workorder.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/{username}")
    public Result<User> getByUsername(@PathVariable String username) {
        User user = userService.getByUsername(username);
        return Result.ok(user);
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterReq req) {
        userService.register(req);
        return Result.ok();
    }
}
