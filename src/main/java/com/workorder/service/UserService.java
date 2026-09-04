package com.workorder.service;

import com.workorder.common.PageResult;
import com.workorder.common.dto.LoginReq;
import com.workorder.common.dto.RegisterReq;
import com.workorder.common.vo.LoginVO;
import com.workorder.common.vo.UserDetailVO;
import com.workorder.entity.User;

import java.util.List;

public interface UserService {

    User getByUsername(String username);

    /** 根据用户名查询用户详情（含角色列表 + 权限码列表）——供前端 F5 刷新同步用 */
    UserDetailVO getUserDetailByUsername(String username);

    LoginVO login(LoginReq req);

    void register(RegisterReq req);

    void assignRoles(Long userId, List<Long> roleIds);

    PageResult<UserDetailVO> listUsers(Integer page, Integer size, String username, Long deptId);

    UserDetailVO getUserDetail(Long userId);
}
