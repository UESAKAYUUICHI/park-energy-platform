package com.parkenergyplatform.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkenergyplatform.common.BusinessException;
import com.parkenergyplatform.dto.LoginRequest;
import com.parkenergyplatform.entity.SysUser;
import com.parkenergyplatform.mapper.SysPermissionMapper;
import com.parkenergyplatform.mapper.SysRoleMapper;
import com.parkenergyplatform.mapper.SysRolePermissionMapper;
import com.parkenergyplatform.mapper.SysUserMapper;
import com.parkenergyplatform.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class RbacServiceTests {
    @Test
    void adminCannotUseTheFormerFallbackPasswordWhenItDoesNotMatchTheDatabase() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUser admin = new SysUser();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setStatus(1);
        admin.setPassword(new BCryptPasswordEncoder().encode("a-different-password"));
        when(userMapper.selectOne(any())).thenReturn(admin);

        RbacService service = new RbacService(
                userMapper,
                mock(SysRoleMapper.class),
                mock(SysPermissionMapper.class),
                mock(SysUserRoleMapper.class),
                mock(SysRolePermissionMapper.class),
                mock(DataScopeService.class),
                mock(JdbcTemplate.class));

        assertThatThrownBy(() -> service.login(new LoginRequest("admin", "123456")))
                .isInstanceOf(BusinessException.class);
    }
}
