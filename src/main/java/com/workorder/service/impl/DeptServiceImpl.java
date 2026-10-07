package com.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.BizException;
import com.workorder.common.ErrorCode;
import com.workorder.common.vo.DeptOptionVO;
import com.workorder.common.vo.DeptVO;
import com.workorder.entity.Dept;
import com.workorder.entity.User;
import com.workorder.mapper.DeptMapper;
import com.workorder.mapper.UserMapper;
import com.workorder.service.DeptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 部门字典实现（2026-10-08）。判据都落在**业务码**上：重名/空名/超长/停用引用 → 400，id 不存在 → 404。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeptServiceImpl implements DeptService {

    private static final int MAX_NAME_LENGTH = 64;

    private final DeptMapper deptMapper;
    private final UserMapper userMapper;

    @Override
    public List<DeptVO> listAll() {
        return deptMapper.selectList(new LambdaQueryWrapper<Dept>().orderByAsc(Dept::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    public List<DeptOptionVO> listEnabled() {
        return deptMapper.selectList(new LambdaQueryWrapper<Dept>()
                        .eq(Dept::getEnabled, 1)
                        .orderByAsc(Dept::getId))
                .stream().map(dept -> new DeptOptionVO(dept.getId(), dept.getName())).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeptVO create(String name) {
        String trimmed = requireName(name);
        assertNameFree(trimmed, null);

        Dept dept = new Dept();
        dept.setName(trimmed);
        dept.setEnabled(1);
        deptMapper.insert(dept);
        return toVO(deptMapper.selectById(dept.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeptVO update(Long id, String name, Integer enabled) {
        Dept dept = deptMapper.selectById(id);
        if (dept == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "部门不存在: id=" + id);
        }
        if (name != null) {
            String trimmed = requireName(name);
            assertNameFree(trimmed, id);
            dept.setName(trimmed);
        }
        if (enabled != null) {
            if (enabled != 0 && enabled != 1) {
                throw new BizException(ErrorCode.BAD_REQUEST, "enabled 只能是 0（停用）或 1（启用）");
            }
            dept.setEnabled(enabled);
            if (enabled == 0) {
                long users = countUsers(id);
                if (users > 0) {
                    // 不强拦（历史数据里本来就有用户挂在这个部门上），但要让调用方看得见引用关系。
                    log.warn("[dept] 停用部门 id={} 仍有 {} 个用户（不停用失败，引用关系保留）", id, users);
                }
            }
        }
        deptMapper.updateById(dept);
        return toVO(deptMapper.selectById(id));
    }

    @Override
    public void assertSelectable(Long deptId) {
        if (deptId == null) {
            return;   // 不选部门仍然允许（注册页"选填"的语义没变）
        }
        Dept dept = deptMapper.selectById(deptId);
        if (dept == null || dept.getEnabled() == null || dept.getEnabled() != 1) {
            // 一句话覆盖两种失败：不存在 / 已停用——对用户是同一件事（这个部门现在选不了）
            throw new BizException(ErrorCode.BAD_REQUEST, "部门不存在或已停用");
        }
    }

    @Override
    public long countUsers(Long deptId) {
        if (deptId == null) {
            return 0L;
        }
        return userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getDeptId, deptId));
    }

    private static String requireName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部门名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部门名称不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private void assertNameFree(String name, Long selfId) {
        Dept existing = deptMapper.selectOne(new LambdaQueryWrapper<Dept>().eq(Dept::getName, name));
        if (existing != null && !existing.getId().equals(selfId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部门名称已存在：" + name);
        }
    }

    private DeptVO toVO(Dept dept) {
        DeptVO vo = new DeptVO();
        vo.setId(dept.getId());
        vo.setName(dept.getName());
        vo.setEnabled(dept.getEnabled());
        vo.setUserCount(countUsers(dept.getId()));
        vo.setCreatedAt(dept.getCreatedAt());
        vo.setUpdatedAt(dept.getUpdatedAt());
        return vo;
    }
}
