package com.workorder.service;

import com.workorder.common.vo.PermissionTreeVO;
import com.workorder.entity.Permission;
import com.workorder.entity.Role;
import com.workorder.mapper.RoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PermissionServiceTest {

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private RoleMapper roleMapper;

    @Test
    @DisplayName("查询全部权限列表——按id排序")
    void testListAll() {
        List<Permission> permissions = permissionService.listAll();
        assertFalse(permissions.isEmpty());
        // 验证按id升序
        for (int i = 1; i < permissions.size(); i++) {
            assertTrue(permissions.get(i).getId() >= permissions.get(i - 1).getId());
        }
    }

    @Test
    @DisplayName("按角色查询权限——返回指定角色的权限列表")
    void testListByRoleId() {
        Role sysAdmin = roleMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Role>()
                        .eq(Role::getRoleCode, "SYS_ADMIN"));
        assertNotNull(sysAdmin);

        List<Permission> perms = permissionService.listByRoleId(sysAdmin.getId());
        assertFalse(perms.isEmpty());
        assertTrue(perms.size() >= 12);
    }

    @Test
    @DisplayName("组装权限树——父节点包含children子节点列表")
    void testBuildTree_structure() {
        List<Permission> flatList = permissionService.listAll();
        List<PermissionTreeVO> tree = permissionService.buildTree(flatList);

        // 验证第一层：parentId=0的根节点
        assertFalse(tree.isEmpty());
        List<Long> rootParentIds = tree.stream().map(PermissionTreeVO::getParentId).distinct().toList();
        assertEquals(1, rootParentIds.size());
        assertEquals(0L, rootParentIds.get(0));

        // 验证至少3个根节点：order:*, system:*, sla:*
        assertTrue(tree.size() >= 3);
        List<String> rootCodes = tree.stream().map(PermissionTreeVO::getPermCode).toList();
        assertTrue(rootCodes.contains("order:*"));
        assertTrue(rootCodes.contains("system:*"));
        assertTrue(rootCodes.contains("sla:*"));

        // 验证第二层：每个根节点都有children
        for (PermissionTreeVO root : tree) {
            assertNotNull(root.getChildren());
            assertFalse(root.getChildren().isEmpty(),
                    "根节点 " + root.getPermCode() + " 应有子节点");
        }

        // 验证order:*下有order:accept等叶子节点
        PermissionTreeVO orderRoot = tree.stream()
                .filter(n -> "order:*".equals(n.getPermCode()))
                .findFirst().orElseThrow();
        List<String> childCodes = orderRoot.getChildren().stream()
                .map(PermissionTreeVO::getPermCode).toList();
        assertTrue(childCodes.contains("order:accept"));
        assertTrue(childCodes.contains("order:start"));
        assertTrue(childCodes.contains("order:complete"));
        assertTrue(childCodes.contains("order:approve"));
        assertTrue(childCodes.contains("order:reject"));
        assertTrue(childCodes.contains("order:assign"));
        assertTrue(childCodes.contains("order:stats"));
    }

    @Test
    @DisplayName("权限树——叶子节点children为空列表而非null")
    void testBuildTree_leafNodesHaveEmptyChildren() {
        List<Permission> flatList = permissionService.listAll();
        List<PermissionTreeVO> tree = permissionService.buildTree(flatList);

        // 取根节点下的第一个子节点，验证children为空列表
        PermissionTreeVO firstChild = tree.get(0).getChildren().get(0);
        assertNotNull(firstChild.getChildren());
        assertTrue(firstChild.getChildren().isEmpty());
    }
}
