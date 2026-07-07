package vip.mate.billing.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.billing.model.BillingDtos;
import vip.mate.billing.model.BillingWalletEntity;
import vip.mate.billing.service.BillingService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;

@Tag(name = "充值付费 - 管理员")
@RestController
@RequestMapping("/api/v1/admin/billing")
@RequiredArgsConstructor
public class BillingAdminController {
    private final BillingService billingService;
    private final AuthService authService;

    @Operation(summary = "管理员手工入账（不接真实支付网关）")
    @PostMapping("/manual-credit")
    @RequireGlobalAdmin
    public R<BillingWalletEntity> manualCredit(@RequestBody BillingDtos.ManualCreditRequest request,
                                               Authentication auth) {
        UserEntity operator = requireUser(auth);
        if (request == null || request.userId() == null) {
            throw new MateClawException("err.billing.user_required", 400, "请选择入账用户");
        }
        UserEntity target = authService.findById(request.userId());
        if (target == null || (target.getDeleted() != null && target.getDeleted() != 0)) {
            throw new MateClawException("err.auth.user_not_found", 404, "用户不存在");
        }
        return R.ok(billingService.manualCredit(operator, request));
    }

    private UserEntity requireUser(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new MateClawException("err.auth.unauthenticated", 401, "Authentication required");
        }
        UserEntity user = authService.findByUsername(auth.getName());
        if (user == null || (user.getDeleted() != null && user.getDeleted() != 0)) {
            throw new MateClawException("err.auth.user_not_found", 404, "用户不存在");
        }
        return user;
    }
}
