package com.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.vo.PermissionTreeVO;
import com.workorder.entity.Permission;
import com.workorder.entity.RolePermission;
import com.workorder.mapper.PermissionMapper;
import com.workorder.mapper.RolePermissionMapper;
import com.workorder.service.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.groupingBy;

@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;

    @Override
    public List<Permission> listAll() {
        return permissionMapper.selectList(
                new LambdaQueryWrapper<Permission>().orderByAsc(Permission::getId));
    }

    @Override
    public List<Permission> listByRoleId(Long roleId) {
        List<Long> permIds = rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId))
                .stream()
                .map(RolePermission::getPermissionId)
                .toList();

        if (permIds.isEmpty()) {
            return List.of();
        }
        return permissionMapper.selectList(
                new LambdaQueryWrapper<Permission>().in(Permission::getId, permIds)
                        .orderByAsc(Permission::getId));
    }

    @Override
    public List<PermissionTreeVO> buildTree(List<Permission> flatList) {
        Map<Long, List<Permission>> grouped = flatList.stream()
                .collect(groupingBy(Permission::getParentId));

        List<PermissionTreeVO> roots = new ArrayList<>();
        for (Permission perm : grouped.getOrDefault(0L, List.of())) {
            PermissionTreeVO node = toVO(perm);
            buildChildren(node, grouped);
            roots.add(node);
        }
        return roots;
    }

    private void buildChildren(PermissionTreeVO parent, Map<Long, List<Permission>> grouped) {
        List<Permission> children = grouped.getOrDefault(parent.getId(), List.of());
        for (Permission child : children) {
            PermissionTreeVO childVO = toVO(child);
            buildChildren(childVO, grouped);
            parent.getChildren().add(childVO);
        }
    }

    private PermissionTreeVO toVO(Permission perm) {
        PermissionTreeVO vo = new PermissionTreeVO();
        vo.setId(perm.getId());
        vo.setPermCode(perm.getPermCode());
        vo.setPermName(perm.getPermName());
        vo.setParentId(perm.getParentId());
        vo.setChildren(new ArrayList<>());
        return vo;
    }
}
