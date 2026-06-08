package com.workorder.service;

import com.workorder.common.dto.LoginReq;
import com.workorder.common.dto.RegisterReq;
import com.workorder.common.vo.LoginVO;
import com.workorder.entity.User;

public interface UserService {

    User getByUsername(String username);

    LoginVO login(LoginReq req);

    void register(RegisterReq req);
}
