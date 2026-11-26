package com.daumjari.role;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.SessionUser;
import com.daumjari.role.ServiceRoleService.AccountRoles;
import com.daumjari.role.ServiceRoleService.ChangePage;
import com.daumjari.role.ServiceRoleService.ChangeRequest;
import com.daumjari.role.ServiceRoleService.RolePage;

/** 서비스 권한 관리. /api/operator/** 는 SecurityConfig 가 운영자만 들이고, 서비스가 잠금 안에서 한 번 더 확인한다. */
@RestController
public class ServiceRoleController {

	private final ServiceRoleService roles;

	public ServiceRoleController(ServiceRoleService roles) {
		this.roles = roles;
	}

	@GetMapping("/api/operator/roles")
	public RolePage list(@RequestParam(required = false) String q, @RequestParam(required = false) String page,
			@RequestParam(required = false) String size) {
		return roles.list(q, page, size);
	}

	@GetMapping("/api/operator/roles/changes")
	public ChangePage changes() {
		return roles.changes();
	}

	@PutMapping("/api/operator/roles/{accountId}/{role}")
	public AccountRoles grant(@PathVariable Long accountId, @PathVariable String role, @RequestBody ChangeRequest request,
			@AuthenticationPrincipal SessionUser user) {
		return roles.change(accountId, role, true, request, user);
	}

	@DeleteMapping("/api/operator/roles/{accountId}/{role}")
	public AccountRoles revoke(@PathVariable Long accountId, @PathVariable String role, @RequestBody ChangeRequest request,
			@AuthenticationPrincipal SessionUser user) {
		return roles.change(accountId, role, false, request, user);
	}

}
