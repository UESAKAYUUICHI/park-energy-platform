package com.parkenergyplatform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.dto.UserOrgScopeItem;
import com.parkenergyplatform.entity.SysUser;
import com.parkenergyplatform.entity.SysUserOrgScope;
import com.parkenergyplatform.mapper.SysUserMapper;
import com.parkenergyplatform.mapper.SysUserOrgScopeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 数据权限边界测试：可见组织解析（超管 / SUBTREE / SELF / 回退）、
 * SQL 片段生成、用户组织绑定替换的校验与去重。
 */
class DataScopeServiceTests {
    private JdbcTemplate jdbc;
    private SysUserMapper userMapper;
    private SysUserOrgScopeMapper scopeMapper;
    private DataScopeService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        userMapper = mock(SysUserMapper.class);
        scopeMapper = mock(SysUserOrgScopeMapper.class);
        service = new DataScopeService(jdbc, userMapper, scopeMapper);
    }

    private void notSuperAdmin(long userId) {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(userId))).thenReturn(0L);
    }

    private void orgTree(Map<Long, List<Long>> children) {
        List<Map<String, Object>> rows = new ArrayList<>();
        children.forEach((parent, ids) -> ids.forEach(id ->
                rows.add(new LinkedHashMap<>(Map.of("id", id, "parent_id", parent)))));
        when(jdbc.queryForList(anyString())).thenReturn(rows);
    }

    @Test
    void superAdminGetsUnrestrictedScopeAndAlwaysPassesAccessCheck() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L))).thenReturn(1L);
        assertThat(service.visibleOrgIds(1L)).isNull();
        assertThat(service.hasOrgAccess(1L, 999L)).isTrue();
    }

    @Test
    void subtreeScopeExpandsToAllDescendantOrgs() {
        notSuperAdmin(2L);
        when(jdbc.queryForList(anyString(), eq(2L))).thenReturn(List.of(
                Map.of("org_id", 9L, "scope_mode", "SUBTREE")));
        orgTree(Map.of(9L, List.of(10L, 11L), 10L, List.of(12L)));

        Set<Long> visible = service.visibleOrgIds(2L);
        assertThat(visible).containsExactlyInAnyOrder(9L, 10L, 11L, 12L);
        assertThat(service.hasOrgAccess(2L, 12L)).isTrue();
        assertThat(service.hasOrgAccess(2L, 99L)).isFalse();
    }

    @Test
    void selfScopeOnlyContainsTheBoundOrgItself() {
        notSuperAdmin(3L);
        when(jdbc.queryForList(anyString(), eq(3L))).thenReturn(List.of(
                Map.of("org_id", 10L, "scope_mode", "SELF")));
        orgTree(Map.of(9L, List.of(10L, 11L)));

        assertThat(service.visibleOrgIds(3L)).containsExactly(10L);
        assertThat(service.hasOrgAccess(3L, 11L)).isFalse();
    }

    @Test
    void userWithoutScopeBindingFallsBackToOwnOrgAsSelfScope() {
        notSuperAdmin(4L);
        when(jdbc.queryForList(anyString(), eq(4L))).thenReturn(List.of());
        SysUser user = new SysUser();
        user.setId(4L);
        user.setOrgId(10L);
        when(userMapper.selectById(4L)).thenReturn(user);
        orgTree(Map.of(9L, List.of(10L, 11L)));

        assertThat(service.visibleOrgIds(4L)).containsExactly(10L);
    }

    @Test
    void userWithoutOrgAndScopeSeesNoOrganizations() {
        notSuperAdmin(5L);
        when(jdbc.queryForList(anyString(), eq(5L))).thenReturn(List.of());
        when(userMapper.selectById(5L)).thenReturn(null);

        assertThat(service.visibleOrgIds(5L)).isEmpty();
        assertThat(service.hasOrgAccess(5L, 9L)).isFalse();
    }

    @Test
    void inClauseBuildsParameterizedSqlForEachIdSetShape() {
        List<Object> args = new ArrayList<>();
        assertThat(service.inClause("o.id", null, args)).isEmpty();
        assertThat(service.inClause("o.id", Set.of(), args)).isEqualTo(" AND 1 = 0");
        assertThat(args).isEmpty();

        String sql = service.inClause("o.id", Set.of(1L, 2L), args);
        assertThat(sql).isEqualTo(" AND o.id IN (?, ?)");
        assertThat(args).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void replaceUserOrgScopesRejectsUnknownUser() {
        when(userMapper.selectById(7L)).thenReturn(null);
        List<UserOrgScopeItem> scopes = List.of(new UserOrgScopeItem(9L, "SUBTREE"));
        assertThatThrownBy(() -> service.replaceUserOrgScopes(7L, scopes))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户不存在");
        verify(scopeMapper, never()).insert(any(SysUserOrgScope.class));
    }

    @Test
    void replaceUserOrgScopesRejectsUnknownOrg() {
        when(userMapper.selectById(7L)).thenReturn(new SysUser());
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(404L))).thenReturn(0L);

        assertThatThrownBy(() -> service.replaceUserOrgScopes(7L, List.of(new UserOrgScopeItem(404L, "SELF"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("组织不存在");
    }

    @Test
    void replaceUserOrgScopesRejectsUnsupportedScopeMode() {
        when(userMapper.selectById(7L)).thenReturn(new SysUser());
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(9L))).thenReturn(1L);

        assertThatThrownBy(() -> service.replaceUserOrgScopes(7L, List.of(new UserOrgScopeItem(9L, "ALL"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SELF 或 SUBTREE");
    }

    @Test
    void replaceUserOrgScopesClearsOldRowsNormalizesAndSkipsDuplicates() {
        when(userMapper.selectById(7L)).thenReturn(new SysUser());
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(9L))).thenReturn(1L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(10L))).thenReturn(1L);

        List<UserOrgScopeItem> scopes = List.of(
                new UserOrgScopeItem(9L, null),
                new UserOrgScopeItem(9L, "self"),
                new UserOrgScopeItem(10L, "subtree"));
        service.replaceUserOrgScopes(7L, scopes);

        verify(scopeMapper).delete(any());
        ArgumentCaptor<SysUserOrgScope> captor = ArgumentCaptor.forClass(SysUserOrgScope.class);
        verify(scopeMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(SysUserOrgScope::getOrgId, SysUserOrgScope::getScopeMode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(9L, "SELF"),
                        org.assertj.core.groups.Tuple.tuple(10L, "SUBTREE"));
    }
}
