package com.workorder.service;

import com.workorder.common.vo.PermissionTreeVO;
import com.workorder.entity.Permission;

import java.util.List;

public interface PermissionService {

    List<Permission> listAll();

    List<Permission> listByRoleId(Long roleId);

    List<PermissionTreeVO> buildTree(List<Permission> flatList);
}
