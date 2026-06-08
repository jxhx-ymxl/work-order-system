package com.workorder.controller;

import com.workorder.common.Result;
import com.workorder.common.dto.LoginReq;
import com.workorder.common.vo.LoginVO;
import com.workorder.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "用户管理")
public class LoginController {

    private final UserService userService;

    @PostMapping("/api/login")
    @Operation(summary = "用户登录")
    public Result<LoginVO> login(@Valid @RequestBody LoginReq req) {
        LoginVO vo = userService.login(req);
        return Result.ok(vo);
    }
}
