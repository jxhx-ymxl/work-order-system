package com.workorder.service;

import com.workorder.common.vo.DeptOptionVO;
import com.workorder.common.vo.DeptVO;

import java.util.List;

/**
 * 部门字典（2026-10-08 部门实体化）。
 *
 * <p>模型选择与不做清单见 `docs/DECISIONS.md` D109；要点：用户/工单继续只存 `dept_id`，
 * 本服务只提供"id → 名称 / 启停"，**没有物理删除**（"删除"= 停用）。
 */
public interface DeptService {

    /** 管理端列表：**含停用项**，每行带 `userCount`。 */
    List<DeptVO> listAll();

    /** 公开列表（注册页下拉用）：只返回启用项，只给 id + 名称。 */
    List<DeptOptionVO> listEnabled();

    /** 新增（名称 trim 非空、≤64、唯一；重名 → 业务码 400）。 */
    DeptVO create(String name);

    /** 改名 / 启停（两个字段都可选；id 不存在 → 404）。 */
    DeptVO update(Long id, String name, Integer enabled);

    /**
     * 校验"这个 deptId 现在可选"：非空时必须**存在且启用**，否则业务码 400。
     *
     * <p>调用点：注册（`UserServiceImpl.register`）。**将来加"管理员改用户部门"的接口必须也走这里**——
     * 现在没有那个接口，所以只有注册一处（D109 已登记）。
     */
    void assertSelectable(Long deptId);

    /** 该部门下的用户数（引用关系的可见化；停用时不强拦，只提示）。 */
    long countUsers(Long deptId);
}
