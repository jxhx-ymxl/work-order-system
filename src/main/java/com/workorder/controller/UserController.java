package com.workorder.controller;

import com.workorder.common.Result;
import com.workorder.common.dto.RegisterReq;
import com.workorder.entity.User;
import com.workorder.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "用户管理")
public class UserController {

    private final UserService userService;

    @GetMapping("/{username}")
    @Operation(summary = "根据用户名查询用户")
    public Result<User> getByUsername(@Parameter(description = "用户名") @PathVariable String username) {
        User user = userService.getByUsername(username);
        return Result.ok(user);
    }

    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public Result<Void> register(@Valid @RequestBody RegisterReq req) {
        userService.register(req);
        return Result.ok();
    }
}
