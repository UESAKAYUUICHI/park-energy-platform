package com.parkenergyplatform.auth;

import java.util.List;

import cn.dev33.satoken.stp.StpInterface;
import com.parkenergyplatform.service.RbacService;
import org.springframework.stereotype.Component;

@Component
public class SaPermissionProvider implements StpInterface {
    private final RbacService rbacService;

    public SaPermissionProvider(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return rbacService.permissionCodes(Long.parseLong(String.valueOf(loginId)));
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return rbacService.roleCodes(Long.parseLong(String.valueOf(loginId)));
    }
}
