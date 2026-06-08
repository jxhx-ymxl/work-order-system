package com.workorder.controller;

import com.workorder.common.Result;
import com.workorder.common.dto.LoginReq;
import com.workorder.common.vo.LoginVO;
import com.workorder.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class LoginController {

    private final UserService userService;

    @PostMapping("/api/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginReq req) {
        LoginVO vo = userService.login(req);
        return Result.ok(vo);
    }
}
