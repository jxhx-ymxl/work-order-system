package com.workorder.service;

import com.workorder.common.dto.RoleCreateReq;
import com.workorder.common.dto.RoleUpdateReq;
import com.workorder.common.vo.RoleVO;
import com.workorder.entity.Role;

import java.util.List;

public interface RoleService {

    Role createRole(RoleCreateReq req);

    Role updateRole(Long id, RoleUpdateReq req);

    void deleteRole(Long id);

    List<Role> listRoles();

    RoleVO getRoleDetail(Long roleId);

    void assignPermissions(Long roleId, List<Long> permIds);
}
